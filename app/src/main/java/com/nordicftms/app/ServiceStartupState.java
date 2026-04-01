package com.nordicftms.app;

enum ServiceStartupState {
    WAITING_FOR_PERMISSION,
    WAITING_FOR_GRPC,
    RUNNING
}
