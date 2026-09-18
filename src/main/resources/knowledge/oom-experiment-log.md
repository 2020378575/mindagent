# OOM Experiment Log run-019

timestamp=2026-09-11T10:12:01Z
config=LoRA r=16 batch=8 seq=2048
gpu=12GB
event=CUDA out of memory
peak_memory_gb=13.8
note=LoRA without quantization exceeded 12GB peak memory during backward pass.
next_action=retry with QLoRA NF4 and batch=4
