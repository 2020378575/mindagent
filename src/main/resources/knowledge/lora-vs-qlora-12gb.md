# LoRA vs QLoRA under 12GB GPU

## Summary
When fine-tuning on a single 12GB consumer GPU, QLoRA with NF4 quantization usually reduces peak memory compared with full-precision LoRA adapters.

## Recommendation
Prefer QLoRA when VRAM is capped at 12GB and the dataset fits an adapter-based setup.
Prefer LoRA only when you already have headroom above 16GB or you need higher adapter precision.

## Constraints
- GPU: 12GB
- Method: LoRA or QLoRA
- Quantization: NF4 for QLoRA
