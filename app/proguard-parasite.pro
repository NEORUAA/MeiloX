# Preserve the isolated WorkManager entry points inspected by the host adapter.
-keep class androidx.work.impl.background.systemjob.SystemJobInfoConverter { *; }
-keep class androidx.work.impl.background.systemjob.SystemJobScheduler {
    static java.util.List getPendingJobs(android.content.Context, android.app.job.JobScheduler);
}
-keep class androidx.work.impl.background.systemjob.JobSchedulerExtKt {
    static android.app.job.JobScheduler getWmJobScheduler(android.content.Context);
}
-keep class androidx.work.impl.utils.PackageManagerHelper {
    public static void setComponentEnabled(android.content.Context, java.lang.Class, boolean);
}

-keep class com.ljyh.mei.parasite.MeiloXModule { public <init>(); }
