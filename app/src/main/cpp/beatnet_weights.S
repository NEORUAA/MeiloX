    .section .rodata.beatnet_weights,"a",%progbits
    .global beatnet_weights_begin
    .global beatnet_weights_end
    .hidden beatnet_weights_begin
    .hidden beatnet_weights_end
    .balign 16
beatnet_weights_begin:
    .incbin "beatnet_weights.bin"
beatnet_weights_end:
