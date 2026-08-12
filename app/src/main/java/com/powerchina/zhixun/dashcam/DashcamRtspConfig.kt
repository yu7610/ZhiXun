/**
 * AI 工程师 ZLMediaKit RTSP 推流配置。
 *
 * 文档示例未写端口；实测主机 554 不通、**8554 开放**（ZLMediaKit 常见端口）。
 * ffmpeg 对齐：-rtsp_transport tcp rtsp://host:8554/aiEngineer/...
 */
object DashcamRtspConfig {

    const val HOST = "123.207.0.188"
    /** ZLMediaKit 默认 RTSP 端口；勿省略（省略会走 554，当前不通） */
    const val PORT = 8554
    const val APP = "aiEngineer"
    const val STREAM_SUFFIX = "camera1"

    /** 与文档 ffmpeg `-rtsp_transport tcp` 一致 */
    const val FORCE_TCP = true

    /**
     * false：`?token=`（推荐，兼容 RootEncoder）
     * true：文档原样 `?/token=`
     */
    const val USE_DOC_QUERY_STYLE = false

    /** 推流鉴权 token（文档提供） */
    const val AUTH_TOKEN = "YWRtaW46eWZ6eGhrMDE"

    /**
     * @param deviceName 设备名/编号，对应路径中的 stream 段（如 ae01 / MAC）
     */
    fun buildPushUrl(deviceName: String): String {
        val stream = deviceStreamName(deviceName)
        val query = if (USE_DOC_QUERY_STYLE) {
            "?/token=$AUTH_TOKEN"
        } else {
            "?token=$AUTH_TOKEN"
        }
        return "rtsp://$HOST:$PORT/$APP/$stream/$STREAM_SUFFIX$query"
    }

    fun deviceStreamName(macOrTerCode: String): String =
        macOrTerCode.trim()
            .replace(":", "")
            .replace("-", "")
            .lowercase()
            .ifBlank { "device" }
}
