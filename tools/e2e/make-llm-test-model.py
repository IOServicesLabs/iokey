#!/usr/bin/env python3
"""
Writes the small models LlmModelTest runs on the test emulator. Their weights are random, so they write word salad,
and that is all the test needs, because it is about the keyboard's flow and not the text.

Why not an ordinary small model: the emulator runs the app's arm64 libraries by translation, and the translator aborts
on half-precision vector conversions when the virtual CPU has no F16C, which this one lacks. Every attention model
keeps its cache in half precision, so it dies on its first decode. An RWKV model has no such cache, and with 32-bit
weights it never converts anything.

Only the LEAP engine can run them there, so LlmModelTest stores them under a catalog name that goes to that engine. The
engine of the other models needs instructions the translator does not have, and no file can change that.

The default model ends a run by itself after a few dozen tokens, in much less than a second. With --slow the file is
about 50 MB and a run lasts several seconds, most of it reading the prompt, and it only stops at the app's token limit,
so that a test has time to stop it.

The vocabulary comes from a public model, tinyllamas/stories260K.gguf at https://huggingface.co/ggml-org/models. Only
its ordinary pieces can win: control tokens and raw bytes have no weights, so they never reach the top of the sampler,
and raw bytes cannot make a draft invalid text.

Needs numpy and the gguf package, which is also under app/src/main/cpp/llama.cpp/gguf-py.

    python tools/e2e/make-llm-test-model.py <stories260K.gguf> <out.gguf> [--slow]
"""

import argparse
import math

import numpy as np
from gguf import GGUFReader, GGUFWriter

ARCH = "rwkv6"
HEAD_SIZE = 8
TOKEN_TYPE_NORMAL = 1
EOS_ID = 2

# The spread of the word pieces' logits. The sampler's min-p then leaves about twenty of them to choose from.
LOGIT_SPREAD = 1.7

# One channel of the output is kept for the end token. The norm's bias makes that channel nearly constant, so the
# chance of ending is about the same at every token and does not depend on where the random weights wander. These put
# the end token's logit near 3.5, a little under the top pieces at about 4.8, which comes to one token in about 25.
END_CHANNEL = 0
END_CHANNEL_BIAS = 7.0
END_CHANNEL_WEIGHT = 0.5


def read_vocab(path):
    reader = GGUFReader(path)
    fields = reader.fields
    tokens = [bytes(fields["tokenizer.ggml.tokens"].parts[i]).decode("utf-8", "surrogateescape")
              for i in fields["tokenizer.ggml.tokens"].data]
    scores = [float(fields["tokenizer.ggml.scores"].parts[i][0]) for i in fields["tokenizer.ggml.scores"].data]
    types = [int(fields["tokenizer.ggml.token_type"].parts[i][0]) for i in fields["tokenizer.ggml.token_type"].data]
    return tokens, scores, types


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vocab_from")
    parser.add_argument("out")
    parser.add_argument("--slow", action="store_true")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    n_embd, n_layer, n_ffn, time_mix_extra, time_decay_extra = (
        (384, 8, 768, 16, 8) if args.slow else (32, 2, 64, 8, 4)
    )

    tokens, scores, types = read_vocab(args.vocab_from)
    n_vocab = len(tokens)
    rng = np.random.default_rng(args.seed)

    def random(shape, scale):
        return (rng.standard_normal(shape) * scale).astype(np.float32)

    def filled(shape, value):
        return np.full(shape, value, dtype=np.float32)

    writer = GGUFWriter(args.out, ARCH)
    writer.add_name("iokey-llm-test")
    writer.add_uint32(f"{ARCH}.context_length", 2048)
    writer.add_uint32(f"{ARCH}.embedding_length", n_embd)
    writer.add_uint32(f"{ARCH}.block_count", n_layer)
    writer.add_uint32(f"{ARCH}.feed_forward_length", n_ffn)
    writer.add_uint32(f"{ARCH}.attention.head_count", 0)
    writer.add_float32(f"{ARCH}.attention.layer_norm_epsilon", 1e-5)
    writer.add_uint32(f"{ARCH}.wkv.head_size", HEAD_SIZE)
    writer.add_uint32(f"{ARCH}.time_mix_extra_dim", time_mix_extra)
    writer.add_uint32(f"{ARCH}.time_decay_extra_dim", time_decay_extra)
    writer.add_uint32(f"{ARCH}.rescale_every_n_layers", 0)
    writer.add_uint32(f"{ARCH}.token_shift_count", 2)

    writer.add_tokenizer_model("llama")
    writer.add_token_list(tokens)
    writer.add_token_scores(scores)
    writer.add_token_types(types)
    writer.add_bos_token_id(1)
    writer.add_eos_token_id(EOS_ID)
    writer.add_unk_token_id(0)

    # Shapes here are numpy's, the reverse of ggml's.
    writer.add_tensor("token_embd.weight", random((n_vocab, n_embd), 0.5))
    writer.add_tensor("token_embd_norm.weight", filled((n_embd,), 1.0))
    writer.add_tensor("token_embd_norm.bias", filled((n_embd,), 0.0))

    # The norm leaves each channel at about 1 either way, so a piece's logit has the spread asked for here.
    output = random((n_vocab, n_embd), LOGIT_SPREAD / math.sqrt(n_embd - 1))
    output[np.array(types) != TOKEN_TYPE_NORMAL] = 0.0
    output[:, END_CHANNEL] = 0.0
    output[EOS_ID] = 0.0
    output_bias = filled((n_embd,), 0.0)
    if not args.slow:
        output[EOS_ID, END_CHANNEL] = END_CHANNEL_WEIGHT
        output_bias[END_CHANNEL] = END_CHANNEL_BIAS
    writer.add_tensor("output_norm.weight", filled((n_embd,), 1.0))
    writer.add_tensor("output_norm.bias", output_bias)
    writer.add_tensor("output.weight", output)

    for layer in range(n_layer):
        blk = f"blk.{layer}"
        writer.add_tensor(f"{blk}.attn_norm.weight", filled((n_embd,), 1.0))
        writer.add_tensor(f"{blk}.attn_norm.bias", filled((n_embd,), 0.0))
        writer.add_tensor(f"{blk}.attn_norm_2.weight", filled((n_embd,), 1.0))
        writer.add_tensor(f"{blk}.attn_norm_2.bias", filled((n_embd,), 0.0))
        writer.add_tensor(f"{blk}.time_mix_w1.weight", random((time_mix_extra * 5, n_embd), 0.1))
        writer.add_tensor(f"{blk}.time_mix_w2.weight", random((5, n_embd, time_mix_extra), 0.1))
        writer.add_tensor(f"{blk}.time_mix_lerp_x.weight", filled((1, 1, n_embd), 0.5))
        writer.add_tensor(f"{blk}.time_mix_lerp_fused.weight", filled((5, 1, 1, n_embd), 0.5))
        writer.add_tensor(f"{blk}.time_mix_first.weight", random((n_embd // HEAD_SIZE, HEAD_SIZE), 0.1))
        writer.add_tensor(f"{blk}.time_mix_decay.weight", filled((n_embd,), -2.0))
        writer.add_tensor(f"{blk}.time_mix_decay_w1.weight", random((time_decay_extra, n_embd), 0.1))
        writer.add_tensor(f"{blk}.time_mix_decay_w2.weight", random((n_embd, time_decay_extra), 0.1))
        writer.add_tensor(f"{blk}.time_mix_key.weight", random((n_embd, n_embd), 0.2))
        writer.add_tensor(f"{blk}.time_mix_value.weight", random((n_embd, n_embd), 0.2))
        writer.add_tensor(f"{blk}.time_mix_receptance.weight", random((n_embd, n_embd), 0.2))
        writer.add_tensor(f"{blk}.time_mix_gate.weight", random((n_embd, n_embd), 0.2))
        writer.add_tensor(f"{blk}.time_mix_ln.weight", filled((n_embd,), 1.0))
        writer.add_tensor(f"{blk}.time_mix_ln.bias", filled((n_embd,), 0.0))
        writer.add_tensor(f"{blk}.time_mix_output.weight", random((n_embd, n_embd), 0.2))
        writer.add_tensor(f"{blk}.channel_mix_lerp_k.weight", filled((1, 1, n_embd), 0.5))
        writer.add_tensor(f"{blk}.channel_mix_lerp_r.weight", filled((1, 1, n_embd), 0.5))
        writer.add_tensor(f"{blk}.channel_mix_key.weight", random((n_ffn, n_embd), 0.2))
        writer.add_tensor(f"{blk}.channel_mix_value.weight", random((n_embd, n_ffn), 0.2))
        writer.add_tensor(f"{blk}.channel_mix_receptance.weight", random((n_embd, n_embd), 0.2))

    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file()
    writer.close()
    print(f"Wrote {args.out}: {n_vocab} tokens, {n_layer} layers of {n_embd}.")


if __name__ == "__main__":
    main()
