package com.ljyh.mei.source.script;

import android.os.ParcelFileDescriptor;
import com.ljyh.mei.source.script.IQuickJsEvaluation;

interface IQuickJsSandbox {
    int openSession(long maxHeapBytes, long stackBytes, int maxResultBytes);
    int getPid();
    oneway void evaluate(int sessionId, long evaluationId, in ParcelFileDescriptor utf8Script, IQuickJsEvaluation callback);
    oneway void cancel(int sessionId, long evaluationId);
    void closeSession(int sessionId);
    oneway void shutdown();
}
