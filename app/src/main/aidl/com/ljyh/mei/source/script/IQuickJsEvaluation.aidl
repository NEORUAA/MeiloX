package com.ljyh.mei.source.script;

import android.os.ParcelFileDescriptor;

oneway interface IQuickJsEvaluation {
    void success(long evaluationId, in ParcelFileDescriptor utf8Result);
    void failure(long evaluationId, int code);
}
