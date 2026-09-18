# Baseline README

## Training baseline
- model: llama-3-8b
- method: QLoRA
- quantization: NF4
- learning_rate: 2e-4
- batch_size: 4
- max_seq_len: 1024

## Success criteria
Peak GPU memory must stay under 12GB and validation loss must not regress versus the previous adapter run.
