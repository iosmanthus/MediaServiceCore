package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.innertube.initialresponse.InitialResponseService;
import com.liskovsoft.youtubeapi.videoinfo.VideoInfoServiceBase;
import com.liskovsoft.youtubeapi.videoinfo.models.CaptionTrack;
import com.liskovsoft.youtubeapi.videoinfo.models.TranslationLanguage;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoHls;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoReel;

import java.util.Arrays;
import java.util.List;

import retrofit2.Call;

public class VideoInfoService extends VideoInfoServiceBase {
    private static final String TAG = VideoInfoService.class.getSimpleName();
    private static final AppClient IOS_CLIENT = AppClient.VISIONOS;
    private static final AppClient TV_CLIENT = AppClient.TV_DOWNGRADED;
    private static final AppClient WEB_CLIENT = AppClient.WEB_EMBED;
    private static VideoInfoService sInstance;
    private final VideoInfoApi mVideoInfoApi;
    private final static AppClient[] VIDEO_INFO_TYPE_LIST = {
            AppClient.WEB_EMBED, // Restricted (18+) videos
            AppClient.VISIONOS, // no url formats
            // NOTE: try the signed-in web context before falling back to the tv clients.
            //
            // On an egress where googlevideo demands a po token, a tv client cannot produce a
            // playable url: PoTokenGate mints tokens only for the web family, so every tv-minted
            // url goes out with pot=null and comes back 403 on every chunk. Measured on a device
            // where an *authorized* TV_DOWNGRADED request with a correct n parameter and pot=null
            // still 403s, while a web client carrying a token does not.
            //
            // The web clients that do get a token cannot use the tv oauth flow, so anonymously
            // they either fail the bot check or are handed an ad-laden SABR stream the player
            // cannot consume. A browser escapes both because it is WEB *and* signed in; these two
            // clients reproduce that by authorizing with cookies. See yuliskov/SmartTube#6030.
            AppClient.WEB_EMBED_AUTH,
            AppClient.WEB_AUTH,
            AppClient.TV_DOWNGRADED, // probably unplayable (weird potoken format?)
            AppClient.TV, // Supports auth. Fixes "please sign in" bug! (the best for Premium users)
            //AppClient.ANDROID_REEL, // doesn't require pot and cipher (hangs on all engines)
            AppClient.WEB, // Fix video clip blocked in current location
            AppClient.WEB_SAFARI,
            AppClient.IOS,
            AppClient.GEO, // Fix video clip blocked in current location
            AppClient.MWEB, // single audio language
            AppClient.TV_LEGACY,
            AppClient.TV_EMBED, // single audio language
            AppClient.ANDROID_VR, // doesn't require pot and cipher (often hangs?)
            AppClient.TV_SIMPLY, // hangs?
            //AppClient.ANDROID_SDK_LESS, // doesn't require pot (hangs on Cronet!)
    };
    @Nullable
    private AppClient mActualInfoType = null;
    @Nullable
    private AppClient mNextInfoType = null;
    private boolean mAuthBlock;
    private List<TranslationLanguage> mCachedTranslationLanguages;
    private boolean mIsUnplayable;

    private VideoInfoService() {
        mVideoInfoApi = RetrofitHelper.create(VideoInfoApi.class);
    }

    public static VideoInfoService instance() {
        if (sInstance == null) {
            sInstance = new VideoInfoService();
        }

        return sInstance;
    }

    public VideoInfo getVideoInfo(String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        //initInfoTypeIfNeeded();
        //reorderTypeListIfNeeded();

        AppService.instance().resetClientPlaybackNonce(); // unique value per each video info

        mAuthBlock = true;

        long t0 = System.currentTimeMillis();

        VideoInfo result = firstPlayable(videoId, clickTrackingParams);

        if (result == null) {
            Log.e(TAG, "Can't get video info. videoId: %s", videoId);
            return null;
        }

        long t1 = System.currentTimeMillis();

        applyFixesIfNeeded(result, videoId, clickTrackingParams);

        long t2 = System.currentTimeMillis();

        transformFormats(result);

        long t3 = System.currentTimeMillis();

        // Where the time between pressing play and the first frame actually goes.
        // Measured as three stages because they fail differently: the fetch is
        // network, the fixes are *extra* round trips the response asked for, and
        // the transform is signature work done on the device.
        com.liskovsoft.mediaserviceinterfaces.diagnostics.ApiDiagnostics.report("video_info_timing",
                "fetch_ms", t1 - t0,
                "fixes_ms", t2 - t1,
                "transform_ms", t3 - t2,
                "hls_extra", shouldObtainExtendedFormats(result) || result.isStoryboardBroken(),
                "subs_extra", needMoreSubtitles(result),
                "formats", result.getAdaptiveFormats() == null ? 0 : result.getAdaptiveFormats().size());

        persistRecentTypeIfNeeded(result);

        mIsUnplayable = result.isUnplayable();

        return result;
    }

    private void reorderTypeListIfNeeded() {
        if (getData().isFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS)) {
            moveFirst(IOS_CLIENT);
        } else {
            moveFirst(WEB_CLIENT);
        }
    }

    private void moveFirst(AppClient client) {
        if (VIDEO_INFO_TYPE_LIST[0] != client) {
            Helpers.move(VIDEO_INFO_TYPE_LIST, Arrays.asList(VIDEO_INFO_TYPE_LIST).indexOf(client), 0);
        }
    }

    public VideoInfo getAuthVideoInfo(String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        mAuthBlock = true;

        // Only the tv client supports auth features
        return getVideoInfo(AppClient.TV, videoId, clickTrackingParams);
    }

    private VideoInfo firstPlayable(String videoId, String clickTrackingParams) {
        // With cookies on hand the authorized web clients are the only ones that can
        // produce a playable url on an egress where googlevideo demands a po token, so
        // this deliberately does not walk. Falling through to a tv client returns a
        // response that claims to be playable and then 403s on every chunk -- and
        // firstInfoWith() would accept it, because "not unplayable" is all it checks.
        // An honest failure is better: the player reports it, and the next attempt has
        // a real chance instead of settling on something that cannot work.
        if (com.liskovsoft.youtubeapi.app.CookieAuthStore.isEnabled()) {
            return retryCookieAuth(videoId, clickTrackingParams);
        }

        VideoInfo result = firstInfoWith(videoId, clickTrackingParams, info -> !info.isUnplayable());

        return result != null ? result : firstInfoWith(videoId, clickTrackingParams, info -> info.getRegularFormats() != null);
    }

    /**
     * How many times to go round the authorized web clients before giving up.
     *
     * Failures here are intermittent rather than sticky -- the same video and the
     * same cookies were observed refused and then accepted a minute apart -- so a
     * couple of retries are worth more than a longer client list. Kept small
     * because the player is waiting on this.
     */
    private static final int COOKIE_AUTH_ROUNDS = 2;
    private static final long COOKIE_AUTH_RETRY_MS = 700;

    /**
     * How long to refuse to try again after a whole run has failed.
     *
     * Returning nothing makes the caller ask again about a second later, so
     * without this the rounds below become a sustained two requests a second
     * for as long as the video stays selected -- and that rate is itself enough
     * to get the session refused as a bot, which then produces more retries.
     * Observed on the device: six requests every three seconds, indefinitely,
     * every one of them answered "please sign in to confirm you are not a bot"
     * while the same cookies worked fine from elsewhere.
     */
    private static final long COOKIE_AUTH_COOLDOWN_MS = 30_000;

    private static long sCookieAuthCooldownUntil;

    private VideoInfo retryCookieAuth(String videoId, String clickTrackingParams) {
        long now = System.currentTimeMillis();

        if (now < sCookieAuthCooldownUntil) {
            com.liskovsoft.mediaserviceinterfaces.diagnostics.ApiDiagnostics.report(
                    "cookie_auth_cooldown", "remaining_ms", sCookieAuthCooldownUntil - now);
            return null;
        }

        AppClient[] clients = { AppClient.WEB_AUTH, AppClient.WEB_EMBED_AUTH };

        for (int round = 0; round < COOKIE_AUTH_ROUNDS; round++) {
            for (AppClient client : clients) {
                VideoInfo result = getVideoInfoWithRentFix(client, videoId, clickTrackingParams);

                reportClientAttempt(client, videoId, result, round);

                if (result != null && !result.isUnplayable()) {
                    sCookieAuthCooldownUntil = 0;
                    return result;
                }
            }

            if (round + 1 < COOKIE_AUTH_ROUNDS) {
                // Drop the po token between rounds. It is the part of the request
                // most likely to be stale, and minting another is cheap next to
                // the video not playing at all.
                PoTokenGate.resetCache(AppClient.WEB_AUTH);

                try {
                    Thread.sleep(COOKIE_AUTH_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        sCookieAuthCooldownUntil = System.currentTimeMillis() + COOKIE_AUTH_COOLDOWN_MS;

        return null;
    }

    private interface InfoTester {
        boolean test(VideoInfo info);
    }

    private VideoInfo firstInfoWith(String videoId, String clickTrackingParams, InfoTester infoTester) {
        //final AppClient beginType = getDefaultClient();
        // With cookies on hand the authorized web client is the one that actually yields
        // playable urls here, so start there instead of grinding through the whole chain.
        // NOTE: the preference has to beat mNextInfoType, not lose to it. That field remembers
        // whichever client was reached last, so a single earlier failure would pin the walk to a
        // tv client forever - exactly the clients whose urls do not work here.
        AppClient preferred = com.liskovsoft.youtubeapi.app.CookieAuthStore.isEnabled()
                ? AppClient.WEB_AUTH : null;
        final AppClient beginType = preferred != null ? preferred
                : (mNextInfoType != null ? mNextInfoType : VIDEO_INFO_TYPE_LIST[0]);
        AppClient nextType = beginType;

        do {
            VideoInfo result = getVideoInfoWithRentFix(nextType, videoId, clickTrackingParams);

            reportClientAttempt(nextType, videoId, result, -1);

            if (result != null && infoTester.test(result)) {
                return result;
            }

            nextType = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, nextType);
        } while (nextType != beginType);

        return null;
    }

    //private void initInfoTypeIfNeeded() {
    //    if (mActualInfoType != null) {
    //        return;
    //    }
    //
    //    restoreVideoInfoType();
    //}

    public void switchNextFormat() {
        //initInfoTypeIfNeeded();

        // Try to reset pot cache for the last video
        if (!mIsUnplayable && mActualInfoType != null && PoTokenGate.resetCache(mActualInfoType)) {
            return;
        }
        // The Premium is likely broken
        //if (getData().isFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS)) {
        //    // Skip additional formats fetching that could produce an error
        //    getData().setFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS, false);
        //    return;
        //}
        // And last, try to switch the client
        nextVideoInfoType();
        //persistVideoInfoType();
    }

    public void switchNextSubtitle() {
        CaptionTrack.sFormat = Helpers.getNextValue(CaptionTrack.CaptionFormat.values(), CaptionTrack.sFormat);
    }

    public void resetInfoType() {
        resetInfoTypeToDefault();
        PoTokenGate.resetCache();
    }

    private void nextVideoInfoType() {
        mNextInfoType = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, mActualInfoType);
    }

    /**
     * Every attempt, not just the winner.
     *
     * Which client finally answered says nothing about why the preferred ones did
     * not, and that is the only question worth asking when playback lands on a
     * client it should not have reached. The token is reported by length: whether
     * one was carried is the interesting part, its value is a credential.
     *
     * @param round retry round for the cookie-auth path, or -1 when walking.
     */
    private void reportClientAttempt(AppClient client, String videoId, VideoInfo result, int round) {
        if (!com.liskovsoft.mediaserviceinterfaces.diagnostics.ApiDiagnostics.isEnabled()) {
            return;
        }

        String pot = PoTokenGate.getPoToken(client, videoId);

        com.liskovsoft.mediaserviceinterfaces.diagnostics.ApiDiagnostics.report("client_attempt",
                "client", client.name(),
                "cookie_auth", client.isCookieAuthSupported()
                        && com.liskovsoft.youtubeapi.app.CookieAuthStore.isEnabled(),
                "round", round,
                "pot_len", pot == null ? 0 : pot.length(),
                "result", result == null ? "no_response"
                        : (result.isUnplayable() ? "unplayable" : "playable"),
                "status", result == null ? "null" : String.valueOf(result.getPlayabilityStatus()),
                "adaptive", result == null || result.getAdaptiveFormats() == null
                        ? 0 : result.getAdaptiveFormats().size());
    }

    private VideoInfo getVideoInfoWithRentFix(AppClient client, String videoId, String clickTrackingParams) {
        VideoInfo result = getVideoInfo(client, videoId, clickTrackingParams);

        if (result != null && result.isRent()) {
            Log.e(TAG, "Found rent content. Show trailer instead...");
            result = getVideoInfo(client, result.getTrailerVideoId(), clickTrackingParams);
        }

        return result;
    }

    private VideoInfo getVideoInfo(AppClient client, String videoId, String clickTrackingParams) {
        VideoInfo result;

        if (client == AppClient.INITIAL) {
            result = InitialResponseService.getVideoInfo(videoId, mAuthBlock);
        } else {
            String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(client, videoId, clickTrackingParams);
            result = getVideoInfo(client, videoInfoQuery);
        }

        if (result != null) {
            result.setClient(client);
        }

        return result;
    }

    private VideoInfo getVideoInfo(AppClient client, String videoInfoQuery) {
        boolean auth = client.isAuthSupported() && mAuthBlock;
        // The web clients cannot use the tv oauth token; they authorize with cookies instead.
        boolean cookieAuth = client.isCookieAuthSupported() && mAuthBlock
                && com.liskovsoft.youtubeapi.app.CookieAuthStore.isEnabled();

        com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper.setCookieAuth(cookieAuth);

        try {
            if (client.isReelClient()) {
                Call<VideoInfoReel> wrapper = mVideoInfoApi.getVideoInfoReel(videoInfoQuery, mAppService.getVisitorData(),
                        client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
                return getVideoInfoReel(wrapper, auth || cookieAuth);
            }

            // Same reason as the body field: our visitorData belongs to a different session
            // than the cookies, and sending it gets the request rejected.
            String visitorId = cookieAuth ? null : mAppService.getVisitorData();
            Call<VideoInfo> wrapper = mVideoInfoApi.getVideoInfo(videoInfoQuery, visitorId,
                    client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
            return getVideoInfo(wrapper, auth || cookieAuth);
        } finally {
            com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper.setCookieAuth(false);
        }
    }

    private @Nullable VideoInfo getVideoInfo(Call<VideoInfo> wrapper, boolean auth) {
        VideoInfo videoInfo = RetrofitHelper.get(wrapper, auth);

        if (videoInfo == null) {
            return null;
        }

        videoInfo.setAuth(auth);

        return videoInfo;
    }

    private @Nullable VideoInfo getVideoInfoReel(Call<VideoInfoReel> wrapper, boolean auth) {
        VideoInfoReel videoInfo = RetrofitHelper.get(wrapper, auth);

        if (videoInfo == null || videoInfo.getVideoInfo() == null) {
            return null;
        }

        videoInfo.getVideoInfo().setAuth(auth);

        return videoInfo.getVideoInfo();
    }

    private VideoInfoHls getVideoInfoIOSHls(String videoId, String clickTrackingParams) {
        String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(IOS_CLIENT, videoId, clickTrackingParams);
        return getVideoInfoHls(IOS_CLIENT, videoInfoQuery);
    }

    private VideoInfoHls getVideoInfoHls(AppClient client, String videoInfoQuery) {
        Call<VideoInfoHls> wrapper = mVideoInfoApi.getVideoInfoHls(videoInfoQuery, mAppService.getVisitorData(),
                client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());

        return RetrofitHelper.get(wrapper, client.isAuthSupported() && mAuthBlock);
    }

    private void applyFixesIfNeeded(VideoInfo result, String videoId, String clickTrackingParams) {
        if (result == null || result.isUnplayable()) {
            return;
        }

        if (shouldObtainExtendedFormats(result) || result.isStoryboardBroken()) {
            Log.d(TAG, "Enable high bitrate formats...");
            mAuthBlock = false;
            VideoInfoHls videoInfoHls = getVideoInfoIOSHls(videoId, clickTrackingParams);
            if (videoInfoHls != null && shouldObtainExtendedFormats(result)) {
                result.setHlsManifestUrl(videoInfoHls.getHlsManifestUrl());
            }
            if (videoInfoHls != null && result.isStoryboardBroken()) {
                result.setStoryboardSpec(videoInfoHls.getStoryboardSpec());
            }
        }

        // TV and others has a limited number of auto generated subtitles
        if (needMoreSubtitles(result)) {
            Log.d(TAG, "Enable full list of auto generated subtitles...");

            if (mCachedTranslationLanguages == null || mCachedTranslationLanguages.size() < 100) {
                mAuthBlock = false;
                VideoInfo webInfo = null;
                try {
                    webInfo = getVideoInfo(AppClient.WEB, videoId, clickTrackingParams);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (webInfo != null) {
                    mCachedTranslationLanguages = webInfo.getTranslationLanguages();
                }
            }

            if (mCachedTranslationLanguages != null) {
                result.setTranslationLanguages(mCachedTranslationLanguages);
            }
        }
    }

    //private void restoreVideoInfoType() {
    //    int videoInfoType = getData().getVideoInfoType();
    //    if (videoInfoType != -1) {
    //        mActualInfoType = videoInfoType < AppClient.values().length ? AppClient.values()[videoInfoType] : null;
    //        if (!Arrays.asList(VIDEO_INFO_TYPE_LIST).contains(mActualInfoType)) {
    //            resetInfoTypeToDefault();
    //        }
    //    } else {
    //        resetInfoTypeToDefault();
    //    }
    //}

    private void resetInfoTypeToDefault() {
        mNextInfoType = null;
        mActualInfoType = VIDEO_INFO_TYPE_LIST[0];
        persistVideoInfoType();
    }

    private void persistVideoInfoType() {
        if (!GlobalPreferences.isInitialized()) {
            return;
        }

        getData().setVideoInfoType(mActualInfoType != null ? mActualInfoType.ordinal() : -1);
    }

    private void persistRecentTypeIfNeeded(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.isUnplayable() || videoInfo.getClient() == mActualInfoType) {
            return;
        }

        mActualInfoType = videoInfo.getClient();
        persistVideoInfoType();
    }

    private static boolean shouldObtainExtendedFormats(VideoInfo result) {
        return getData().isFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS) && result.isExtendedHlsFormatsBroken();
    }

    private static boolean shouldUnlockMoreSubtitles(VideoInfo videoInfo) {
        return videoInfo != null && videoInfo.hasSubtitles() && getData().isMoreSubtitlesUnlocked();
    }

    private static boolean needMoreSubtitles(VideoInfo videoInfo) {
        return videoInfo != null && videoInfo.hasSubtitles() && (videoInfo.getTranslationLanguages() == null || videoInfo.getTranslationLanguages().size() < 100);
    }

    private static boolean isAuthSupported(AppClient client) {
        return client != null && client.isAuthSupported();
    }
}
