package com.ljyh.mei.runtime

import com.ljyh.mei.parasite.HostPlaybackReportBridge
import com.ljyh.mei.parasite.HostRequestBridge

/** Bootstrap accessors that exist only in the parasite graph. */
interface RuntimeComponent {
    fun hostRequests(): HostRequestBridge
    fun hostPlaybackReports(): HostPlaybackReportBridge
    fun hostCloudUploads(): com.ljyh.mei.parasite.HostCloudBinaryUploader
}
