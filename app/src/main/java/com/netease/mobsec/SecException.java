package com.netease.mobsec;



public class SecException extends java.lang.RuntimeException {
    public static final int b = -100;
    int a;

    public SecException(int i) {
        this.a = i;
    }

    public int getErrorCode() {
        return this.a;
    }

    @Override // java.lang.Throwable
    public void printStackTrace(java.io.PrintStream printStream) {
        printStream.println("ErrorCode = " + getErrorCode());
        super.printStackTrace(printStream);
    }

    public void setErrorCode(int i) {
        this.a = i;
    }

    public SecException(java.lang.String str, int i) {
        super(str);
        this.a = i;
    }

    @Override // java.lang.Throwable
    public void printStackTrace(java.io.PrintWriter printWriter) {
        printWriter.println("ErrorCode = " + getErrorCode());
        super.printStackTrace(printWriter);
    }

    public SecException(java.lang.String str, java.lang.Throwable th, int i) {
        super(str, th);
        this.a = i;
    }

    public SecException(java.lang.Throwable th, int i) {
        super(th);
        this.a = i;
    }
}
