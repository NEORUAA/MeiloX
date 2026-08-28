#if defined(__APPLE__)
    .section __DATA,__const
    .globl _beatnet_weights_begin
    .globl _beatnet_weights_end
    .p2align 4
_beatnet_weights_begin:
    .incbin "beatnet_weights.bin"
_beatnet_weights_end:
#else
    .section .rodata.beatnet_weights,"a",%progbits
    .global beatnet_weights_begin
    .global beatnet_weights_end
    .hidden beatnet_weights_begin
    .hidden beatnet_weights_end
    .balign 16
beatnet_weights_begin:
    .incbin "beatnet_weights.bin"
beatnet_weights_end:
#endif
