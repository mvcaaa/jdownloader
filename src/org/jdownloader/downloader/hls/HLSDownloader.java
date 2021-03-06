package org.jdownloader.downloader.hls;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jd.config.SubConfiguration;
import jd.controlling.downloadcontroller.DiskSpaceReservation;
import jd.controlling.downloadcontroller.ExceptionRunnable;
import jd.controlling.downloadcontroller.FileIsLockedException;
import jd.controlling.downloadcontroller.ManagedThrottledConnectionHandler;
import jd.http.Browser;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.download.DownloadInterface;
import jd.plugins.download.DownloadLinkDownloadable;
import jd.plugins.download.Downloadable;
import jd.plugins.download.raf.FileBytesMap;

import org.appwork.exceptions.WTFException;
import org.appwork.net.protocol.http.HTTPConstants;
import org.appwork.net.protocol.http.HTTPConstants.ResponseCode;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.Application;
import org.appwork.utils.Files;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.logging2.LogInterface;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.net.HTTPHeader;
import org.appwork.utils.net.httpserver.HttpServer;
import org.appwork.utils.net.httpserver.handler.HttpRequestHandler;
import org.appwork.utils.net.httpserver.requests.GetRequest;
import org.appwork.utils.net.httpserver.requests.PostRequest;
import org.appwork.utils.net.httpserver.responses.HttpResponse;
import org.appwork.utils.net.throttledconnection.MeteredThrottledInputStream;
import org.appwork.utils.speedmeter.AverageSpeedMeter;
import org.jdownloader.controlling.UniqueAlltimeID;
import org.jdownloader.controlling.ffmpeg.FFMpegException;
import org.jdownloader.controlling.ffmpeg.FFMpegProgress;
import org.jdownloader.controlling.ffmpeg.FFmpeg;
import org.jdownloader.controlling.ffmpeg.FFmpegMetaData;
import org.jdownloader.controlling.ffmpeg.FFmpegSetup;
import org.jdownloader.controlling.ffmpeg.FFprobe;
import org.jdownloader.controlling.ffmpeg.json.Stream;
import org.jdownloader.controlling.ffmpeg.json.StreamInfo;
import org.jdownloader.downloader.hls.M3U8Playlist.M3U8Segment;
import org.jdownloader.plugins.DownloadPluginProgress;
import org.jdownloader.plugins.SkipReason;
import org.jdownloader.plugins.SkipReasonException;
import org.jdownloader.translate._JDT;

//http://tools.ietf.org/html/draft-pantos-http-live-streaming-13
public class HLSDownloader extends DownloadInterface {

    private volatile long                           bytesWritten   = 0l;
    private final DownloadLinkDownloadable          downloadable;
    private final DownloadLink                      link;
    private long                                    startTimeStamp;
    private final LogInterface                      logger;
    private URLConnectionAdapter                    currentConnection;
    private final ManagedThrottledConnectionHandler connectionHandler;
    private File                                    outputCompleteFile;
    private File                                    outputFinalCompleteFile;
    private File                                    outputPartFile;

    private PluginException                         caughtPluginException;
    private final String                            m3uUrl;
    private HttpServer                              server;

    private final Browser                           obr;

    protected volatile long                         duration       = -1l;
    protected volatile int                          bitrate        = -1;
    private long                                    processID;
    protected MeteredThrottledInputStream           meteredThrottledInputStream;
    protected final AtomicReference<byte[]>         instanceBuffer = new AtomicReference<byte[]>();
    private final boolean                           isTwitch;
    private final boolean                           isTwitchOptimized;

    public HLSDownloader(final DownloadLink link, Browser br2, String m3uUrl) {
        this.m3uUrl = Request.getLocation(m3uUrl, br2.getRequest());
        this.obr = br2.cloneBrowser();
        this.link = link;
        logger = initLogger(link);
        isTwitch = "twitch.tv".equals(link.getHost());
        isTwitchOptimized = isTwitch && Boolean.TRUE.equals(SubConfiguration.getConfig(link.getHost()).getBooleanProperty("expspeed", false));
        connectionHandler = new ManagedThrottledConnectionHandler();
        downloadable = new DownloadLinkDownloadable(link) {
            @Override
            public boolean isResumable() {
                return link.getBooleanProperty("RESUME", true);
            }

            @Override
            public void setResumeable(boolean value) {
                link.setProperty("RESUME", value);
                super.setResumeable(value);
            }
        };
    }

    public LogInterface initLogger(final DownloadLink link) {
        PluginForHost plg = link.getLivePlugin();
        if (plg == null) {
            plg = link.getDefaultPlugin();
        }
        return plg == null ? null : plg.getLogger();
    }

    protected void terminate() {
        if (terminated.getAndSet(true) == false) {
            if (!externalDownloadStop()) {
                if (logger != null) {
                    logger.severe("A critical Downloaderror occured. Terminate...");
                }
            }
        }
    }

    public StreamInfo getProbe() throws IOException {
        initPipe();
        try {
            final FFprobe ffmpeg = new FFprobe();
            this.processID = new UniqueAlltimeID().getID();
            return ffmpeg.getStreamInfo("http://127.0.0.1:" + server.getPort() + "/m3u8?id=" + processID);
        } finally {
            server.stop();
        }
    }

    protected String guessFFmpegFormat(final StreamInfo streamInfo) {
        if (streamInfo != null && streamInfo.getStreams() != null) {
            for (final Stream s : streamInfo.getStreams()) {
                if ("video".equalsIgnoreCase(s.getCodec_type())) {
                    return "mp4";
                }
            }
        }
        return null;
    }

    protected String getFFmpegFormat(FFmpeg ffmpeg) throws PluginException, IOException, InterruptedException, FFMpegException {
        String name = link.getForcedFileName();
        if (StringUtils.isEmpty(name)) {
            name = link.getFinalFileName();
            if (StringUtils.isEmpty(name)) {
                name = link.getRawName();
            }
            if (StringUtils.isEmpty(name)) {
                final String url = link.getContentUrlOrPatternMatcher();
                name = Plugin.extractFileNameFromURL(url);
            }
        }
        String format = ffmpeg.getDefaultFormatByFileName(name);
        if (format == null) {
            final StreamInfo streamInfo = getProbe();
            format = guessFFmpegFormat(streamInfo);
            if (format == null) {
                final String extension = Files.getExtension(name);
                if (extension == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final String extensionID = extension.toLowerCase(Locale.ENGLISH);
                final FFmpegSetup config = JsonConfig.create(FFmpegSetup.class);
                synchronized (HLSDownloader.class) {
                    HashMap<String, String> map = config.getExtensionToFormatMap();
                    if (map == null) {
                        map = new HashMap<String, String>();
                    } else {
                        map = new HashMap<String, String>(map);
                    }
                    try {
                        format = map.get(extensionID);
                        if (format == null) {
                            final ArrayList<String> queryDefaultFormat = new ArrayList<String>();
                            queryDefaultFormat.add(ffmpeg.getFullPath());
                            final File dummy = Application.getTempResource("ffmpeg_dummy-" + System.currentTimeMillis() + "." + extension);
                            try {
                                queryDefaultFormat.add(dummy.getAbsolutePath());
                                queryDefaultFormat.add("-y");
                                ffmpeg.runCommand(null, queryDefaultFormat);
                            } finally {
                                dummy.delete();
                            }
                        }
                    } catch (FFMpegException e) {
                        final String res = e.getError();
                        format = new Regex(res, "Output \\#0\\, ([^\\,]+)").getMatch(0);
                        if (format == null) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, null, -1, e);
                        }
                        map.put(extensionID, format);
                        config.setExtensionToFormatMap(map);
                    }
                }
            }
        }
        return format;
    }

    public void run() throws IOException, PluginException {
        link.setDownloadSize(-1);
        final FFMpegProgress set = new FFMpegProgress();
        try {
            final FFmpeg ffmpeg = new FFmpeg() {
                protected void parseLine(boolean stdStream, StringBuilder ret, String line) {
                    System.out.println(line);
                    try {
                        final String trimmedLine = line.trim();
                        if (trimmedLine.startsWith("Duration:")) {
                            if (!line.contains("Duration: N/A")) {
                                String duration = new Regex(line, "Duration\\: (.*?).?\\d*?\\, start").getMatch(0);
                                HLSDownloader.this.duration = formatStringToMilliseconds(duration);
                            }
                        } else if (trimmedLine.startsWith("Stream #")) {
                            final String bitrate = new Regex(line, "(\\d+) kb\\/s").getMatch(0);
                            if (bitrate != null) {
                                if (HLSDownloader.this.bitrate == -1) {
                                    HLSDownloader.this.bitrate = 0;
                                }
                                HLSDownloader.this.bitrate += Integer.parseInt(bitrate);
                            }
                        } else if (trimmedLine.startsWith("Output #0")) {
                            if (duration > 0 && bitrate > 0) {
                                link.setDownloadSize(((duration / 1000) * bitrate * 1024) / 8);
                            }
                        } else if (trimmedLine.startsWith("frame=") || trimmedLine.startsWith("size=")) {
                            final String size = new Regex(line, "size=\\s*(\\S+)\\s+").getMatch(0);
                            long newSize = SizeFormatter.getSize(size);
                            bytesWritten = newSize;
                            downloadable.setDownloadBytesLoaded(bytesWritten);
                            final String time = new Regex(line, "time=\\s*(\\S+)\\s+").getMatch(0);
                            // final String bitrate = new Regex(line, "bitrate=\\s*([\\d\\.]+)").getMatch(0);
                            long timeInSeconds = (formatStringToMilliseconds(time) / 1000);
                            if (timeInSeconds > 0 && duration > 0) {
                                long rate = bytesWritten / timeInSeconds;
                                link.setDownloadSize(((duration / 1000) * rate));
                            } else {
                                link.setDownloadSize(bytesWritten);
                            }
                        }
                    } catch (Throwable e) {
                        if (logger != null) {
                            logger.log(e);
                        }
                    }
                };
            };
            final String format = getFFmpegFormat(ffmpeg);
            final String out = outputPartFile.getAbsolutePath();
            try {
                initPipe();
                processID = new UniqueAlltimeID().getID();
                runFF(set, format, ffmpeg, out);
            } catch (FFMpegException e) {
                // some systems have problems with special chars to find the in or out file.
                if (e.getError() != null && e.getError().contains("No such file or directory")) {
                    final File tmpOut = Application.getTempResource("ffmpeg_out" + UniqueAlltimeID.create());
                    runFF(set, format, ffmpeg, tmpOut.getAbsolutePath());
                    outputPartFile.delete();
                    tmpOut.renameTo(outputPartFile);
                } else {
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            if (logger != null) {
                logger.log(e);
            }
        } catch (final FFMpegException e) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT, e.getMessage(), -1, e);
        } catch (Throwable e) {
            if (logger != null) {
                logger.log(e);
            }
        } finally {
            if (meteredThrottledInputStream != null) {
                connectionHandler.removeThrottledConnection(meteredThrottledInputStream);
            }
            // link.removePluginProgress(set);
            server.stop();
        }
    }

    protected boolean isValidSegment(final String segment) {
        if (StringUtils.isNotEmpty(segment)) {
            if (isTwitch && StringUtils.endsWithCaseInsensitive(segment, "end_offset=-1")) {
                return false;
            }
            return true;
        }
        return false;
    }

    private final String toExtInfDuration(final long duration) {
        String value = Long.toString(duration);
        switch (value.length()) {
        case 0:
            value = "0.000";
            break;
        case 1:
            value = "0.00" + value;
            break;
        case 2:
            value = "0.0" + value;
            break;
        case 3:
            value = "0." + value;
            break;
        default:
            value = value.replaceFirst("(\\d{3})$", ".$1").replaceFirst("^\\.", "0.");
            break;
        }
        return value;
    }

    protected String optimizeM3U8Playlist(String m3u8Playlist) {
        if (m3u8Playlist != null) {
            if (isTwitchOptimized) {
                final StringBuilder sb = new StringBuilder();
                long lastSegmentDuration = 0;
                String lastSegment = null;
                long lastSegmentStart = -1;
                long lastSegmentEnd = -1;
                long lastMergedSegmentDuration = 0;
                final long maxSegmentSize = 10000000l;// server-side limit
                for (final String line : Regex.getLines(m3u8Playlist)) {
                    if (line.matches("^https?://.+") || !line.trim().startsWith("#")) {
                        final String segment = new Regex(line, "^(.*?)(\\?|$)").getMatch(0);
                        final String segmentStart = new Regex(line, "\\?.*?start_offset=(-?\\d+)").getMatch(0);
                        final String segmentEnd = new Regex(line, "\\?.*?end_offset=(-?\\d+)").getMatch(0);
                        if ("-1".equals(segmentEnd)) {
                            continue;
                        }
                        if (lastSegment != null && !lastSegment.equals(segment) || segmentStart == null || segmentEnd == null || lastSegmentEnd != Long.parseLong(segmentStart) - 1 || Long.parseLong(segmentEnd) - lastSegmentStart > maxSegmentSize) {
                            if (lastSegment != null) {
                                if (sb.length() > 0) {
                                    sb.append("\n");
                                }
                                sb.append("#EXTINF:");
                                sb.append(toExtInfDuration(lastMergedSegmentDuration));
                                lastMergedSegmentDuration = 0;
                                sb.append(",\n");
                                sb.append(lastSegment + "?start_offset=" + lastSegmentStart + "&end_offset=" + lastSegmentEnd);
                                lastSegment = null;
                            }
                        }
                        if (segment != null && segmentStart != null && segmentEnd != null) {
                            if (lastSegment == null) {
                                lastSegment = segment;
                                lastSegmentStart = Long.parseLong(segmentStart);
                                lastSegmentEnd = Long.parseLong(segmentEnd);
                                lastMergedSegmentDuration = lastSegmentDuration;
                            } else {
                                lastSegmentEnd = Long.parseLong(segmentEnd);
                                lastMergedSegmentDuration += lastSegmentDuration;
                            }
                        } else {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append("#EXTINF:");
                            sb.append(toExtInfDuration(lastSegmentDuration));
                            lastSegmentDuration = 0;
                            sb.append(",\n");
                            sb.append(line);
                        }
                    } else {
                        if (line.startsWith("#EXT-X-ENDLIST")) {
                            if (lastSegment != null) {
                                if (sb.length() > 0) {
                                    sb.append("\n");
                                }
                                sb.append("#EXTINF:");
                                sb.append(toExtInfDuration(lastMergedSegmentDuration));
                                lastMergedSegmentDuration = 0;
                                sb.append(",\n");
                                sb.append(lastSegment + "?start_offset=" + lastSegmentStart + "&end_offset=" + lastSegmentEnd);
                                lastSegment = null;
                            }
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(line);
                        } else if (line.startsWith("#EXTINF:")) {
                            final String duration = new Regex(line, "#EXTINF:(\\d+(\\.\\d+)?)").getMatch(0);
                            if (duration != null) {
                                if (duration.contains(".")) {
                                    lastSegmentDuration = Long.parseLong(duration.replace(".", ""));
                                } else {
                                    lastSegmentDuration = Long.parseLong(duration) * 1000;
                                }
                            }
                        } else {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(line);
                        }
                    }
                }
                if (lastSegment != null) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append("#EXTINF:");
                    sb.append(toExtInfDuration(lastMergedSegmentDuration));
                    lastMergedSegmentDuration = 0;
                    sb.append(",\n");
                    sb.append(lastSegment + "?start_offset=" + lastSegmentStart + "&end_offset=" + lastSegmentEnd);
                    lastSegment = null;
                }
                return sb.toString();
            }
        }
        return m3u8Playlist;
    }

    protected boolean isMapMetaDataEnabled() {
        return false;
    }

    protected boolean requiresAdtstoAsc(final String format, final FFmpeg ffmpeg) {
        return ffmpeg.requiresAdtstoAsc(format);
    }

    protected ArrayList<String> buildCommandLine(String format, FFmpeg ffmpeg, String out) {
        final ArrayList<String> l = new ArrayList<String>();
        l.add(ffmpeg.getFullPath());
        l.add("-analyzeduration");// required for low bandwidth streams!
        l.add("15000000");// 15 secs
        l.add("-i");
        l.add("http://127.0.0.1:" + server.getPort() + "/m3u8?id=" + processID);
        if (isMapMetaDataEnabled()) {
            final FFmpegMetaData ffMpegMetaData = getFFmpegMetaData();
            if (ffMpegMetaData != null && !ffMpegMetaData.isEmpty()) {
                l.add("-i");
                l.add("http://127.0.0.1:" + server.getPort() + "/meta?id=" + processID);
                l.add("-map_metadata");
                l.add("1");
            }
        }
        if (requiresAdtstoAsc(format, ffmpeg)) {
            l.add("-bsf:a");
            l.add("aac_adtstoasc");
        }
        l.add("-c");
        l.add("copy");
        l.add("-f");
        l.add(format);
        l.add(out);
        l.add("-y");
        l.add("-progress");
        l.add("http://127.0.0.1:" + server.getPort() + "/progress?id=" + processID);
        return l;
    }

    protected void runFF(FFMpegProgress set, String format, FFmpeg ffmpeg, String out) throws IOException, InterruptedException, FFMpegException {
        ffmpeg.runCommand(set, buildCommandLine(format, ffmpeg, out));
    }

    protected FFmpegMetaData getFFmpegMetaData() {
        return null;
    }

    private volatile M3U8Playlist m3u8Playlists = new M3U8Playlist();

    public M3U8Playlist getM3U8Playlist() {
        return m3u8Playlists;
    }

    private void initPipe() throws IOException {
        server = new HttpServer(0);
        server.setLocalhostOnly(true);
        final HttpServer finalServer = server;
        server.start();
        instanceBuffer.set(new byte[512 * 1024]);
        finalServer.registerRequestHandler(new HttpRequestHandler() {

            @Override
            public boolean onPostRequest(PostRequest request, HttpResponse response) {
                try {
                    if (logger != null) {
                        logger.info(request.toString());
                    }
                    if (processID != Long.parseLong(request.getParameterbyKey("id"))) {
                        return false;
                    }
                    if ("/progress".equals(request.getRequestedPath())) {
                        BufferedReader f = new BufferedReader(new InputStreamReader(request.getInputStream(), "UTF-8"));
                        final StringBuilder ret = new StringBuilder();
                        final String sep = System.getProperty("line.separator");
                        String line;
                        while ((line = f.readLine()) != null) {
                            if (ret.length() > 0) {
                                ret.append(sep);
                            } else if (line.startsWith("\uFEFF")) {
                                /*
                                 * Workaround for this bug: http://bugs.sun.com/view_bug.do?bug_id=4508058
                                 * http://bugs.sun.com/view_bug.do?bug_id=6378911
                                 */

                                line = line.substring(1);
                            }

                            ret.append(line);
                        }
                        response.setResponseCode(ResponseCode.SUCCESS_OK);
                        return true;

                    }

                } catch (Exception e) {
                    if (logger != null) {
                        logger.log(e);
                    }
                }
                return false;
            }

            @Override
            public boolean onGetRequest(GetRequest request, HttpResponse response) {
                boolean requestOkay = false;
                try {
                    if (logger != null) {
                        logger.info("START " + request.getRequestedURL());
                    }
                    if (logger != null) {
                        logger.info(request.toString());
                    }
                    final String id = request.getParameterbyKey("id");
                    if (id == null) {
                        return false;
                    }
                    if (processID != Long.parseLong(request.getParameterbyKey("id"))) {
                        return false;
                    }
                    if ("/meta".equals(request.getRequestedPath())) {
                        final FFmpegMetaData ffMpegMetaData = getFFmpegMetaData();
                        final byte[] bytes;
                        if (ffMpegMetaData != null) {
                            final String content = ffMpegMetaData.getFFmpegMetaData();
                            bytes = content.getBytes("UTF-8");
                        } else {
                            bytes = new byte[0];
                        }
                        if (bytes.length > 0) {
                            response.setResponseCode(HTTPConstants.ResponseCode.get(200));
                        } else {
                            response.setResponseCode(HTTPConstants.ResponseCode.get(404));
                        }
                        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_TYPE, "text/plain; charset=utf-8"));
                        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_LENGTH, String.valueOf(bytes.length)));
                        final OutputStream out = response.getOutputStream(true);
                        if (bytes.length > 0) {
                            out.write(bytes);
                            out.flush();
                        }
                        requestOkay = true;
                        return true;
                    } else if ("/m3u8".equals(request.getRequestedPath())) {
                        final Browser br = obr.cloneBrowser();
                        // work around for longggggg m3u pages
                        final int was = obr.getLoadLimit();
                        // lets set the connection limit to our required request
                        br.setLoadLimit(Integer.MAX_VALUE);
                        final String playlist;
                        try {
                            playlist = optimizeM3U8Playlist(br.getPage(m3uUrl));
                        } finally {
                            // set it back!
                            br.setLoadLimit(was);
                        }
                        response.setResponseCode(HTTPConstants.ResponseCode.get(br.getRequest().getHttpConnection().getResponseCode()));
                        final StringBuilder sb = new StringBuilder();
                        boolean containsEndList = false;
                        final M3U8Playlist m3u8Playlists = new M3U8Playlist();
                        long lastSegmentDuration = -1;
                        for (final String line : Regex.getLines(playlist)) {
                            if (StringUtils.isEmpty(line)) {
                                continue;
                            }
                            if (StringUtils.startsWithCaseInsensitive(line, "concat") || StringUtils.contains(line, "file:")) {
                                // http://habrahabr.ru/company/mailru/blog/274855/
                                logger.severe("possibly malicious: " + line);
                            } else if (line.matches("^https?://.+") || !line.trim().startsWith("#")) {
                                final String segmentURL = br.getURL(line).toString();
                                if (!m3u8Playlists.containsSegmentURL(segmentURL)) {
                                    if (isValidSegment(line)) {
                                        final int index = m3u8Playlists.addSegment(segmentURL, lastSegmentDuration);
                                        if (sb.length() > 0) {
                                            sb.append("\n");
                                        }
                                        sb.append("http://127.0.0.1:" + finalServer.getPort() + "/download?id=" + processID + "&ts_index=" + index);
                                    } else if (logger != null) {
                                        logger.info("Segment '" + line + "' is invalid!");
                                    }
                                }
                                lastSegmentDuration = -1;
                            } else {
                                if (line.startsWith("#EXTINF:")) {
                                    final String duration = new Regex(line, "#EXTINF:(\\d+(\\.\\d+)?)").getMatch(0);
                                    if (duration != null) {
                                        if (duration.contains(".")) {
                                            lastSegmentDuration = Long.parseLong(duration.replace(".", ""));
                                        } else {
                                            lastSegmentDuration = Long.parseLong(duration) * 1000;
                                        }
                                    }
                                } else if ("#EXT-X-ENDLIST".equals(line)) {
                                    containsEndList = true;
                                } else if (StringUtils.containsIgnoreCase(line, "EXT-X-KEY")) {
                                    link.setProperty("ENCRYPTED", true);
                                }
                                if (sb.length() > 0) {
                                    sb.append("\n");
                                }
                                sb.append(line);
                            }
                        }
                        if (!containsEndList) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append("#EXT-X-ENDLIST");
                            sb.append("\n\n");
                        }
                        HLSDownloader.this.m3u8Playlists = m3u8Playlists;
                        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_TYPE, br.getRequest().getHttpConnection().getContentType()));
                        byte[] bytes = sb.toString().getBytes("UTF-8");
                        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_LENGTH, String.valueOf(bytes.length)));
                        OutputStream out = response.getOutputStream(true);
                        out.write(bytes);
                        out.flush();
                        requestOkay = true;
                        return true;
                    } else if ("/download".equals(request.getRequestedPath())) {
                        final String indexString = request.getParameterbyKey("ts_index");
                        if (indexString == null) {
                            return false;
                        }
                        M3U8Segment segment = null;
                        try {
                            final int index = Integer.parseInt(indexString);
                            segment = m3u8Playlists.getSegment(index);
                            if (segment == null) {
                                throw new IndexOutOfBoundsException("Unknown segment:" + index);
                            } else {
                                if (logger != null) {
                                    logger.info("Forward segment:" + index + "/" + m3u8Playlists.size());
                                }
                            }
                        } catch (final NumberFormatException e) {
                            if (logger != null) {
                                logger.log(e);
                            }
                            return false;
                        } catch (final IndexOutOfBoundsException e) {
                            if (logger != null) {
                                logger.log(e);
                            }
                            return false;
                        }
                        OutputStream outputStream = null;
                        final FileBytesMap fileBytesMap = new FileBytesMap();
                        final Browser br = obr.cloneBrowser();
                        retryLoop: for (int retry = 0; retry < 10; retry++) {
                            try {
                                br.disconnect();
                            } catch (final Throwable e) {
                            }
                            final jd.http.requests.GetRequest getRequest = new jd.http.requests.GetRequest(segment.getUrl());
                            if (fileBytesMap.getFinalSize() > 0) {
                                if (logger != null) {
                                    logger.info("Resume(" + retry + "): " + fileBytesMap.toString());
                                }
                                final List<Long[]> unMarkedAreas = fileBytesMap.getUnMarkedAreas();
                                getRequest.getHeaders().put(HTTPConstants.HEADER_REQUEST_RANGE, "bytes=" + unMarkedAreas.get(0)[0] + "-" + unMarkedAreas.get(0)[1]);
                            }
                            URLConnectionAdapter connection = null;
                            try {
                                connection = br.openRequestConnection(getRequest);
                                if (connection.getResponseCode() != 200 && connection.getResponseCode() != 206) {
                                    throw new IOException("ResponseCode must be 200 or 206!");
                                }
                            } catch (IOException e) {
                                if (logger != null) {
                                    logger.log(e);
                                }
                                if (connection == null || connection.getResponseCode() == 504) {
                                    Thread.sleep(250 + (retry * 50));
                                    continue retryLoop;
                                } else {
                                    if (isTwitchOptimized && connection != null && connection.getResponseCode() == 400) {
                                        SubConfiguration.getConfig(link.getHost()).setProperty("expspeed", false);
                                    }
                                    return false;
                                }
                            }
                            byte[] readWriteBuffer = HLSDownloader.this.instanceBuffer.getAndSet(null);
                            final boolean instanceBuffer;
                            if (readWriteBuffer != null) {
                                instanceBuffer = true;
                            } else {
                                instanceBuffer = false;
                                readWriteBuffer = new byte[32 * 1024];
                            }
                            final long length = connection.getCompleteContentLength();
                            try {
                                if (outputStream == null) {
                                    response.setResponseCode(HTTPConstants.ResponseCode.get(br.getRequest().getHttpConnection().getResponseCode()));
                                    if (length > 0) {
                                        fileBytesMap.setFinalSize(length);
                                        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_LENGTH, Long.toString(length)));
                                    }
                                    response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_TYPE, connection.getContentType()));
                                    outputStream = response.getOutputStream(true);
                                }
                                if (meteredThrottledInputStream == null) {
                                    meteredThrottledInputStream = new MeteredThrottledInputStream(connection.getInputStream(), new AverageSpeedMeter(10));
                                    if (connectionHandler != null) {
                                        connectionHandler.addThrottledConnection(meteredThrottledInputStream);
                                    }
                                } else {
                                    meteredThrottledInputStream.setInputStream(connection.getInputStream());
                                }
                                long position = fileBytesMap.getMarkedBytes();
                                while (true) {
                                    int len = -1;
                                    try {
                                        len = meteredThrottledInputStream.read(readWriteBuffer);
                                    } catch (IOException e) {
                                        if (fileBytesMap.getFinalSize() > 0) {
                                            Thread.sleep(250 + (retry * 50));
                                            continue retryLoop;
                                        } else {
                                            throw e;
                                        }
                                    }
                                    if (len > 0) {
                                        outputStream.write(readWriteBuffer, 0, len);
                                        segment.setLoaded(true);
                                        fileBytesMap.mark(position, len);
                                        position += len;
                                    } else if (len == -1) {
                                        break;
                                    }
                                }
                                outputStream.flush();
                                outputStream.close();
                                if (fileBytesMap.getSize() > 0) {
                                    requestOkay = fileBytesMap.getUnMarkedBytes() == 0;
                                } else {
                                    requestOkay = true;
                                }
                                return true;
                            } finally {
                                if (segment != null && (connection.getResponseCode() == 200 || connection.getResponseCode() == 206)) {
                                    segment.setSize(Math.max(connection.getCompleteContentLength(), fileBytesMap.getSize()));
                                }
                                if (instanceBuffer) {
                                    HLSDownloader.this.instanceBuffer.compareAndSet(null, readWriteBuffer);
                                }
                                connection.disconnect();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    if (logger != null) {
                        logger.log(e);
                    }
                } catch (IOException e) {
                    if (logger != null) {
                        logger.log(e);
                    }
                } finally {
                    if (logger != null) {
                        logger.info("END:" + requestOkay + ">" + request.getRequestedURL());
                    }
                }
                return true;
            }
        });

    }

    public long getBytesLoaded() {
        return bytesWritten;
    }

    @Override
    public ManagedThrottledConnectionHandler getManagedConnetionHandler() {
        return connectionHandler;
    }

    @Override
    public URLConnectionAdapter connect(Browser br) throws Exception {
        throw new WTFException("Not needed");
    }

    @Override
    public long getTotalLinkBytesLoadedLive() {
        return getBytesLoaded();
    }

    @Override
    public boolean startDownload() throws Exception {
        try {
            downloadable.setDownloadInterface(this);
            DownloadPluginProgress downloadPluginProgress = null;
            downloadable.setConnectionHandler(this.getManagedConnetionHandler());
            final DiskSpaceReservation reservation = downloadable.createDiskSpaceReservation();
            try {
                if (!downloadable.checkIfWeCanWrite(new ExceptionRunnable() {

                    @Override
                    public void run() throws Exception {
                        downloadable.checkAndReserve(reservation);
                        createOutputChannel();
                        try {
                            downloadable.lockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
                        } catch (FileIsLockedException e) {
                            downloadable.unlockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
                            throw new PluginException(LinkStatus.ERROR_ALREADYEXISTS);
                        }
                    }
                }, null)) {
                    throw new SkipReasonException(SkipReason.INVALID_DESTINATION);
                }
                startTimeStamp = System.currentTimeMillis();
                downloadPluginProgress = new DownloadPluginProgress(downloadable, this, Color.GREEN.darker());
                downloadable.addPluginProgress(downloadPluginProgress);
                downloadable.setAvailable(AvailableStatus.TRUE);
                run();
            } finally {
                try {
                    downloadable.free(reservation);
                } catch (final Throwable e) {
                    LogSource.exception(logger, e);
                }
                try {
                    downloadable.addDownloadTime(System.currentTimeMillis() - getStartTimeStamp());
                } catch (final Throwable e) {
                }
                downloadable.removePluginProgress(downloadPluginProgress);
            }
            onDownloadReady();
            return handleErrors();
        } finally {
            downloadable.unlockFiles(outputCompleteFile, outputFinalCompleteFile, outputPartFile);
            cleanupDownladInterface();
        }

    }

    protected void error(PluginException pluginException) {
        synchronized (this) {
            /* if we recieved external stop, then we dont have to handle errors */
            if (externalDownloadStop()) {
                return;
            }
            LogSource.exception(logger, pluginException);
            if (caughtPluginException == null) {
                caughtPluginException = pluginException;
            }
        }
        terminate();
    }

    protected void onDownloadReady() throws Exception {
        cleanupDownladInterface();
        if (!handleErrors() && !isAcceptDownloadStopAsValidEnd()) {
            return;
        }
        final boolean renameOkay = downloadable.rename(outputPartFile, outputCompleteFile);
        if (!renameOkay) {
            error(new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, _JDT.T.system_download_errors_couldnotrename(), LinkStatus.VALUE_LOCAL_IO_ERROR));
        }
    }

    protected void cleanupDownladInterface() {
        try {
            downloadable.removeConnectionHandler(this.getManagedConnetionHandler());
        } catch (final Throwable e) {
        }
        try {
            final URLConnectionAdapter currentConnection = getConnection();
            if (currentConnection != null) {
                currentConnection.disconnect();
            }
        } catch (Throwable e) {
        }
    }

    private boolean handleErrors() throws PluginException {
        if (externalDownloadStop()) {
            return false;
        }
        if (!isAcceptDownloadStopAsValidEnd()) {
            for (int index = 0; index < m3u8Playlists.size(); index++) {
                if (!m3u8Playlists.isSegmentLoaded(index)) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE);
                }
            }
        }
        if (caughtPluginException == null) {
            downloadable.setLinkStatus(LinkStatus.FINISHED);
            final long fileSize = outputCompleteFile.length();
            downloadable.setDownloadBytesLoaded(fileSize);
            downloadable.setVerifiedFileSize(fileSize);
            return true;
        } else {
            throw caughtPluginException;
        }
    }

    private void createOutputChannel() throws SkipReasonException {
        try {
            final String fileOutput = downloadable.getFileOutput();
            if (logger != null) {
                logger.info("createOutputChannel for " + fileOutput);
            }
            final String finalFileOutput = downloadable.getFinalFileOutput();
            outputCompleteFile = new File(fileOutput);
            outputFinalCompleteFile = outputCompleteFile;
            if (!fileOutput.equals(finalFileOutput)) {
                outputFinalCompleteFile = new File(finalFileOutput);
            }
            outputPartFile = new File(downloadable.getFileOutputPart());
        } catch (Exception e) {
            LogSource.exception(logger, e);
            throw new SkipReasonException(SkipReason.INVALID_DESTINATION, e);
        }
    }

    @Override
    public URLConnectionAdapter getConnection() {
        return currentConnection;
    }

    @Override
    public void stopDownload() {
        if (abort.getAndSet(true) == false) {
            if (logger != null) {
                logger.info("externalStop recieved");
            }
            terminate();
        }
    }

    private final AtomicBoolean abort                        = new AtomicBoolean(false);
    private final AtomicBoolean terminated                   = new AtomicBoolean(false);
    /**
     * if set to true, external Stops will finish and rename the file, else the file will be handled as unfinished. This is usefull for live
     * streams since
     */
    private boolean             acceptDownloadStopAsValidEnd = false;

    @Override
    public boolean externalDownloadStop() {
        return abort.get();
    }

    @Override
    public long getStartTimeStamp() {
        return startTimeStamp;
    }

    @Override
    public void close() {
        final URLConnectionAdapter currentConnection = getConnection();
        if (currentConnection != null) {
            currentConnection.disconnect();
        }
    }

    @Override
    public Downloadable getDownloadable() {
        return downloadable;
    }

    @Override
    public boolean isResumedDownload() {
        return false;
    }

    public void setAcceptDownloadStopAsValidEnd(boolean b) {
        this.acceptDownloadStopAsValidEnd = b;
    }

    public boolean isAcceptDownloadStopAsValidEnd() {
        return acceptDownloadStopAsValidEnd;
    }

}
