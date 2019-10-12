package com.yuyuko.selector;

public class ChannelAlreadyClosedException extends RuntimeException {
    public ChannelAlreadyClosedException(String message) {
        super(message);
    }
}
