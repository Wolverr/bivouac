#!/system/bin/sh
# MTP speculative decoding vs plain decoding on the phone, same prompt, 200 tokens.
#   sh mtp.sh <mtp-model.gguf> <label> [extra args]
#   baseline: sh mtp.sh m.gguf base --spec-draft-n-max 0   (or compare with bench.sh -n 200)
#   MTP:      sh mtp.sh m.gguf mtp2 --spec-type draft-mtp --spec-draft-n-max 2
# Pin threads to the big cores with -t N -C <mask> (see cores.sh) for stable numbers.
cd /data/local/tmp
M=$1; L=$2; shift 2
printf "<|im_start|>user\nExplain how vaccines train the immune system, in about 200 words.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n" > mtp_prompt.txt
./llama-spec -m "$M" -f mtp_prompt.txt -n 200 -c 2048 --temp 0 --no-warmup "$@" > mtp.out 2> mtp.err
echo "=== $L"
grep -E "decoded|accept|n_draft|speed" mtp.err | head -8
grep -iE "error|failed" mtp.err | head -3
