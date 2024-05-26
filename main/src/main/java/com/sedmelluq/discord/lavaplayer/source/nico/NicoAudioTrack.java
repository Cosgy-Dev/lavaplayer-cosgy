package com.sedmelluq.discord.lavaplayer.source.nico;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Audio track that handles processing NicoNico tracks.
 */
public class NicoAudioTrack extends DelegatedAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(NicoAudioTrack.class);

    private static String actionTrackId = "S1G2fKdzOl_1702504390263";

    private final NicoAudioSourceManager sourceManager;

    private String heartbeatUrl;
    private int heartbeatIntervalMs;
    private String initialHeartbeatPayload;

    /**
     * @param trackInfo     Track info
     * @param sourceManager Source manager which was used to find this track
     */
    public NicoAudioTrack(AudioTrackInfo trackInfo, NicoAudioSourceManager sourceManager) {
        super(trackInfo);

        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            String playbackUrl = loadPlaybackUrl(httpInterface);

            log.debug("Starting NicoNico track from URL: {}", playbackUrl);
            processDelegate(new HlsStreamTrack(
                trackInfo,
                extractHlsAudioPlaylistUrl(httpInterface, playbackUrl),
                sourceManager.getHttpInterfaceManager(),
                true
            ), localExecutor);
        }
    }

    private String extractHlsAudioPlaylistUrl(HttpInterface httpInterface, String videoPlaylistUrl) throws IOException {
        String url = null;
        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(videoPlaylistUrl))) {
            int statusCode = response.getStatusLine().getStatusCode();

            if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                throw new FriendlyException("Server responded with an error.", SUSPICIOUS,
                    new IllegalStateException("Response code for track access info is " + statusCode));
            }

            String bodyString = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            for (String rawLine : bodyString.split("\n")) {
                ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(rawLine);
                if (Objects.equals(line.directiveName, "EXT-X-MEDIA")
                    && Objects.equals(line.directiveArguments.get("TYPE"), "AUDIO")) {
                    url = line.directiveArguments.get("URI");
                    break;
                }
            }
        }

        if (url == null) throw new FriendlyException("Failed to find audio playlist URL.", SUSPICIOUS,
            new IllegalStateException("Valid audio directive was not found"));

        return url;
    }

    private JsonBrowser loadVideoApi(HttpInterface httpInterface) throws IOException {
        String apiUrl = "https://www.nicovideo.jp/api/watch/v3_guest/" + getIdentifier() + "?_frontendId=6&_frontendVersion=0&actionTrackId=" + actionTrackId + "&i18nLanguage=ja-jp";

        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(apiUrl))) {
            HttpClientTools.assertSuccessWithContent(response, "api response");

            return JsonBrowser.parse(response.getEntity().getContent()).get("data");
        }
    }

    private JsonBrowser loadVideoMainPage(HttpInterface httpInterface) throws IOException {
        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(trackInfo.uri))) {
            HttpClientTools.assertSuccessWithContent(response, "video main page");

            String urlEncodedData = DataFormatTools.extractBetween(EntityUtils.toString(response.getEntity()), "data-api-data=\"", "\"");
            String watchData = Parser.unescapeEntities(urlEncodedData, false);

            return JsonBrowser.parse(watchData);
        }
    }

    private String loadPlaybackUrl(HttpInterface httpInterface) throws IOException {
        JsonBrowser videoJson = loadVideoApi(httpInterface);

        if (videoJson.isNull()) {
            log.warn("Couldn't retrieve NicoNico video details from API, falling back to HTML page...");
            videoJson = loadVideoMainPage(httpInterface);
        }

        if (!videoJson.isNull()) {
            // an "actionTrackId" is necessary to receive an API response.
            // We make sure this is kept up to date to prevent any issues with tracking IDs becoming invalid.
            String trackingId = videoJson.get("client").get("watchTrackId").text();

            if (trackingId != null) {
                actionTrackId = trackingId;
            }
        }

        //JSONObject watchData = processJSON(videoJson.get("media").get("delivery").get("movie").get("session"));
        String video = videoJson.get("media").get("domand").get("videos").index(1).get("id").text();
        String audio = videoJson.get("media").get("domand").get("audios").index(0).get("id").text();

        log.info("Video ID: {} Audio ID:{}", video, audio);
        System.out.println("Video ID: " + video + " Audio ID: " + audio);
        String jwt = videoJson.get("media").get("domand").get("accessRightKey").text();

        log.info("JWT: {}", jwt);
        System.out.println("JWT: " + jwt);

        HttpPost request = new HttpPost("https://nvapi.nicovideo.jp/v1/watch/" + getIdentifier() + "/access-rights/hls?actionTrackId=" + actionTrackId);
        request.addHeader("Content-Type", "application/json");
        request.addHeader("X-Access-Right-Key", jwt);
        request.addHeader("X-Frontend-Version", "0");
        request.addHeader("X-Frontend-Id", "6");
        request.addHeader("X-Request-With", "https://www.nicovideo.jp");
        request.addHeader("Sec-Fetch-Dest", "empty");
        request.addHeader("Sec-Fetch-Mode", "cors");
        request.addHeader("Sec-Fetch-Site", "same-site");
        request.addHeader("Origin", "https://www.nicovideo.jp");
        request.addHeader("Referer", "https://www.nicovideo.jp/");
        request.addHeader("X-Niconico-Language", "ja-jp");
        request.setEntity(new StringEntity("{\"outputs\": [[\"" + video + "\", \""+ audio +"\"]]}"));

        try (CloseableHttpResponse response = httpInterface.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode != HttpStatus.SC_CREATED) {
                throw new IOException("Unexpected status code from playback parameters page: " + statusCode);
            }

            JsonBrowser info = JsonBrowser.parse(response.getEntity().getContent()).get("data");
            /*JsonBrowser session = info.get("session");

            heartbeatUrl = "https://api.dmc.nico/api/sessions/" + session.get("id").text() + "?_format=json&_method=PUT";
            heartbeatIntervalMs = session.get("keep_method").get("heartbeat").get("lifetime").asInt(120000) - 5000;
            initialHeartbeatPayload = info.format();
             */

            return info.get("contentUri").text();
        }
    }

    private JSONObject processJSON(JsonBrowser input) {
        if (input.isNull()) {
            throw new IllegalStateException("Invalid response received from NicoNico when loading video details");
        }

        JSONObject lifetime = new JSONObject().put("lifetime", input.get("heartbeatLifetime").asLong(120000));
        JSONObject heartbeat = new JSONObject().put("heartbeat", lifetime);

        List<String> videos = input.get("videos").values().stream()
            .map(JsonBrowser::text)
            .collect(Collectors.toList());

        List<String> audios = input.get("audios").values().stream()
            .map(JsonBrowser::text)
            .collect(Collectors.toList());

        JSONObject srcIds = new JSONObject()
            .put("video_src_ids", videos)
            .put("audio_src_ids", audios);

        JSONObject srcIdToMux = new JSONObject().put("src_id_to_mux", srcIds);
        JSONArray array = new JSONArray().put(srcIdToMux);
        JSONObject contentSrcIds = new JSONObject().put("content_src_ids", array);
        JSONArray contentSrcIdSets = new JSONArray().put(contentSrcIds);

        JsonBrowser url = input.get("urls").index(0);
        boolean useWellKnownPort = url.get("isWellKnownPort").asBoolean(false);
        boolean useSsl = url.get("isSsl").asBoolean(false);

        JSONObject httpDownloadParameters = new JSONObject()
            .put("use_well_known_port", useWellKnownPort ? "yes" : "no")
            .put("use_ssl", useSsl ? "yes" : "no");

        JSONObject innerParameters = new JSONObject()
            .put("http_output_download_parameters", httpDownloadParameters);

        JSONObject httpParameters = new JSONObject().put("parameters", innerParameters);
        JSONObject outerParameters = new JSONObject().put("http_parameters", httpParameters);

        JSONObject protocol = new JSONObject()
            .put("name", "http")
            .put("parameters", outerParameters);

        JSONObject sessionOperationAuthBySignature = new JSONObject()
            .put("token", input.get("token").text())
            .put("signature", input.get("signature").text());

        JSONObject sessionOperationAuth = new JSONObject()
            .put("session_operation_auth_by_signature", sessionOperationAuthBySignature);

        JSONObject contentAuth = new JSONObject()
            .put("auth_type", input.get("authTypes").get("http").text())
            .put("content_key_timeout", input.get("contentKeyTimeout").asLong(120000))
            .put("service_id", "nicovideo")
            .put("service_user_id", input.get("serviceUserId").text());

        JSONObject clientInfo = new JSONObject().put("player_id", input.get("playerId").text());

        JSONObject session = new JSONObject()
            .put("content_type", "movie")
            .put("timing_constraint", "unlimited")
            .put("recipe_id", input.get("recipeId").text())
            .put("content_id", input.get("contentId").text())
            .put("keep_method", heartbeat)
            .put("content_src_id_sets", contentSrcIdSets)
            .put("protocol", protocol)
            .put("session_operation_auth", sessionOperationAuth)
            .put("content_auth", contentAuth)
            .put("client_info", clientInfo);

        return new JSONObject().put("session", session);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new NicoAudioTrack(trackInfo, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
