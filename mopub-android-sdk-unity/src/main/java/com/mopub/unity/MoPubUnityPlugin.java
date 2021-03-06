package com.mopub.unity;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.mopub.common.MediationSettings;
import com.mopub.common.MoPub;
import com.mopub.common.MoPubAdvancedBidder;
import com.mopub.common.SdkConfiguration;
import com.mopub.common.SdkInitializationListener;
import com.mopub.common.privacy.ConsentData;
import com.mopub.common.privacy.ConsentDialogListener;
import com.mopub.common.privacy.ConsentStatus;
import com.mopub.common.privacy.ConsentStatusChangeListener;
import com.mopub.common.privacy.PersonalInfoManager;
import com.mopub.mobileads.MoPubConversionTracker;
import com.mopub.mobileads.MoPubErrorCode;
import com.unity3d.player.UnityPlayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;


/**
 * Base class for every available ad format plugin. Exposes APIs that the plugins might need.
 * Operations and properties that apply to the SDK as a whole are here as static methods.
 */
public class MoPubUnityPlugin {
    protected static String TAG = "MoPub";


    protected enum UnityEvent {
        // Init
        SdkInitialized("SdkInitialized"),
        // Consent
        ConsentDialogLoaded("ConsentDialogLoaded"),
        ConsentDialogFailed("ConsentDialogFailed"),
        ConsentDialogShown("ConsentDialogShown"),
        ConsentStatusChanged("ConsentStatusChanged"),
        // Banners
        AdLoaded("AdLoaded"),
        AdFailed("AdFailed"),
        AdClicked("AdClicked"),
        AdExpanded("AdExpanded"),
        AdCollapsed("AdCollapsed"),
        // Interstitials
        InterstitialLoaded("InterstitialLoaded"),
        InterstitialFailed("InterstitialFailed"),
        InterstitialShown("InterstitialShown"),
        InterstitialClicked("InterstitialClicked"),
        InterstitialDismissed("InterstitialDismissed"),
        // Rewarded Videos
        RewardedVideoLoaded("RewardedVideoLoaded"),
        RewardedVideoFailed("RewardedVideoFailed"),
        RewardedVideoShown("RewardedVideoShown"),
        RewardedVideoClicked("RewardedVideoClicked"),
        RewardedVideoFailedToPlay("RewardedVideoFailedToPlay"),
        RewardedVideoClosed("RewardedVideoClosed"),
        RewardedVideoReceivedReward("RewardedVideoReceivedReward"),
        // Native Ads
        NativeImpression("NativeImpression"),
        NativeClick("NativeClick"),
        NativeLoad("NativeLoad"),
        NativeFail("NativeFail");

        @NonNull final private String name;

        UnityEvent(@NonNull final String name) { this.name = "Emit" + name + "Event"; }

        public void Emit(String... args) {
            JSONArray array = new JSONArray();
            for (String arg: args) { array.put(arg); }
            String argstr = array.toString();
            Log.d(TAG, "Sending message to Unity: MoPubManager#" + name + argstr);
            UnityPlayer.UnitySendMessage("MoPubManager", name, argstr);
        }
    }


    private static final ConsentStatusChangeListener consentListener =
        new ConsentStatusChangeListener() {
            @Override
            public void onConsentStateChange(@NonNull ConsentStatus oldConsentStatus,
                                             @NonNull ConsentStatus newConsentStatus,
                                             boolean canCollectPersonalInformation) {
                UnityEvent.ConsentStatusChanged.Emit(
                    oldConsentStatus.getValue(), newConsentStatus.getValue(),
                        canCollectPersonalInformation ? "true" : "false"
                );
            }
        };


    private static final String CHARTBOOST_MEDIATION_SETTINGS =
            "com.mopub.mobileads.ChartboostRewardedVideo$ChartboostMediationSettings";
    private static final String VUNGLE_MEDIATION_SETTINGS =
            "com.mopub.mobileads.VungleRewardedVideo$VungleMediationSettings$Builder";
    private static final String ADCOLONY_GLOBAL_MEDIATION_SETTINGS =
            "com.mopub.mobileads.AdColonyRewardedVideo$AdColonyGlobalMediationSettings";
    private static final String ADCOLONY_INSTANCE_MEDIATION_SETTINGS =
            "com.mopub.mobileads.AdColonyRewardedVideo$AdColonyInstanceMediationSettings";
    private static final String ADMOB_MEDIATION_SETTINGS =
            "com.mopub.mobileads.GooglePlayServicesRewardedVideo$GooglePlayServicesMediationSettings";

    // TODO: see if we can't get MoPub to add an accessor for their is-initialized bool, and get rid of this one.
    private static boolean mIsSdkInitialized = false;


    /**
     * The ad unit that this plugin class manages the content for.
     */
    protected final String mAdUnitId;


    /**
     * Subclasses should use this to create instances of themselves.
     *
     * @param adUnitId String for the ad unit ID to use for the plugin.
     */
    public MoPubUnityPlugin(final String adUnitId) {
        mAdUnitId = adUnitId;
    }


    /**
     * Initializes the MoPub SDK. Call this before making any rewarded ads or advanced bidding
     * requests. This will do the rewarded video custom event initialization any number of times,
     * but the SDK itself can only be initialized once, and the rewarded ads module can only be
     * initialized once.
     *
     * @param adUnitId String with any ad unit id used by this app.
     * @param advancedBiddersString String of comma-separated advanced bidder adapter classes.
     * @param mediationSettingsJson String with JSON containing third-party network specific settings.
     * @param networksToInitString String of comma-separated network rewarded video adapter classes.
     */
    public static void initializeSdk(final String adUnitId, final String advancedBiddersString,
            final String mediationSettingsJson, final String networksToInitString) {
        final SdkConfiguration sdkConfiguration = new SdkConfiguration.Builder(adUnitId)
                .withAdvancedBidders(extractAdvancedBiddersClasses(advancedBiddersString))
                .withMediationSettings(extractMediationSettingsFromJson(mediationSettingsJson, false))
                .withNetworksToInit(Arrays.asList(networksToInitString.split(",")))
                .build();

        final SdkInitializationListener initListener = new SdkInitializationListener() {
            @Override
            public void onInitializationFinished() {
                UnityEvent.SdkInitialized.Emit(adUnitId);
            }
        };

        runSafelyOnUiThread(new Runnable() {
            public void run() {
                MoPub.initializeSdk(getActivity(), sdkConfiguration, initListener);
                mIsSdkInitialized = true;
                // Note: the following call is valid only after initializeSdk() returns, as that
                // is when the PersonalInformationManager property gets initialized.
                MoPub.getPersonalInformationManager().subscribeConsentStatusChangeListener(consentListener);
            }
        });
    }


    public static boolean isSdkInitialized() { return mIsSdkInitialized; }

    public static void setAdvancedBiddingEnabled(final boolean advancedBiddingEnabled) {
        runSafelyOnUiThread(new Runnable() {
            public void run() {
                MoPub.setAdvancedBiddingEnabled(advancedBiddingEnabled);
            }
        });
    }


    public static boolean isAdvancedBiddingEnabled() {
        return MoPub.isAdvancedBiddingEnabled();
    }


    /**
     * Registers the given device as a Facebook Ads test device.
     * See https://developers.facebook.com/docs/reference/android/current/class/AdSettings/
     *
     * @param hashedDeviceId String with the hashed ID of the device.
     */
    public static void addFacebookTestDeviceId(String hashedDeviceId) {
        try {
            Class<?> cls = Class.forName("com.facebook.ads.AdSettings");
            Method method = cls.getMethod("addTestDevice", String.class);
            method.invoke(cls, hashedDeviceId);
            Log.i(TAG, "successfully added Facebook test device: " + hashedDeviceId);
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "could not find Facebook AdSettings class. " +
                    "Did you add the Audience Network SDK to your Android folder?");
        } catch (NoSuchMethodException e) {
            Log.i(TAG, "could not find Facebook AdSettings.addTestDevice method. " +
                    "Did you add the Audience Network SDK to your Android folder?");
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }


    /**
     * Reports an application being open for conversion tracking purposes.
     */
    public static void reportApplicationOpen() {
        runSafelyOnUiThread(new Runnable() {
            public void run() {
                new MoPubConversionTracker(getActivity()).reportAppOpen();
            }
        });
    }


    /**
     * Specifies the desired location awareness settings: DISABLED, TRUNCATED or NORMAL.
     *
     * @param locationAwareness String with location awareness setting to be parsed as a
     *      {@link com.mopub.common.MoPub.LocationAwareness}.
     */
    public static void setLocationAwareness(final String locationAwareness) {
        runSafelyOnUiThread(new Runnable() {
            public void run() {
                MoPub.setLocationAwareness(MoPub.LocationAwareness.valueOf(locationAwareness));
            }
        });
    }


    /* ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** *****
     * Consent Methods                                                                          *
     * ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** *****/

    /**
     * Whether or not the SDK is allowed to collect personally identifiable information.
     *
     * @return true if able to collect personally identifiable information.
     */
    public static boolean canCollectPersonalInfo() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        return pim != null && pim.canCollectPersonalInformation();
    }


    /**
     * The user's current consent state.
     *
     * @return The string value of the ConsentStatus representing the current consent status.
     */
    @Nullable
    public static String getPersonalInfoConsentState() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        return pim != null ? pim.getPersonalInfoConsentStatus().getValue() : null;
    }


    /**
     * Returns whether or not the SDK thinks the user is in a GDPR region or not. Returns true for
     * in a GDPR region, no for not in a GDPR region, and null for unknown.
     *
     * @return +1 for in GDPR region, -1 for not in GDPR region, 0 for unknown
     */
    public static int gdprApplies() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        Boolean applies = pim != null ? pim.gdprApplies() : null;
        return applies == null ? 0 : applies ? 1 : -1;
    }

    /**
     * Forces the SDK to treat this app as in a GDPR region. Setting this will permanently force
     * GDPR rules for this user unless this app is uninstalled or the data for this app is cleared.
     */
    public static void forceGdprApplies() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        if (pim == null) {
            Log.e(TAG, "Failed to force GDPR applicability; did you initialize the MoPub " +
                    "SDK?");
        } else {
            pim.forceGdprApplies();
        }
    }

    /**
     * Checks to see if a publisher should load and then show a consent dialog.
     *
     * @return True for yes, false for no.
     */
    public static boolean shouldShowConsentDialog() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        return pim != null && pim.shouldShowConsentDialog();
    }


    /**
     * Whether or not the consent dialog is done loading and ready to show.
     *
     * @return True for yes, false for no.
     */
    public static boolean isConsentDialogReady() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        return pim != null && pim.isConsentDialogReady();
    }


    /**
     * Sends off a request to load the MoPub consent dialog.
     */
    public static void loadConsentDialog() {
        runSafelyOnUiThread(new Runnable() {
            @Override
            public void run() {
                PersonalInfoManager pim = MoPub.getPersonalInformationManager();
                if (pim != null) pim.loadConsentDialog(new ConsentDialogListener() {
                    @Override
                    public void onConsentDialogLoaded() {
                        UnityEvent.ConsentDialogLoaded.Emit();
                    }

                    @Override
                    public void onConsentDialogLoadFailed(@NonNull MoPubErrorCode moPubErrorCode) {
                        UnityEvent.ConsentDialogFailed.Emit(moPubErrorCode.toString());
                    }
                });
            }
        });
    }


    /**
     * If the MoPub consent dialog is loaded, then show it.
     */
    public static void showConsentDialog() {
        runSafelyOnUiThread(new Runnable() {
            @Override
            public void run() {
                PersonalInfoManager pim = MoPub.getPersonalInformationManager();
                if (pim != null && pim.showConsentDialog()) {
                    UnityEvent.ConsentDialogShown.Emit();
                }
            }
        });
    }


    @Nullable
    public static String getCurrentPrivacyPolicyLink(String isoLanguageCode) {
        return getNullableConsentData().getCurrentPrivacyPolicyLink(isoLanguageCode);
    }


    @Nullable
    public static String getCurrentVendorListLink(String isoLanguageCode) {
        return getNullableConsentData().getCurrentVendorListLink(isoLanguageCode);
    }


    /**
     * For use from whitelisted publishers only. Grants consent to collect personally identifiable
     * information for the current user.
     */
    public static void grantConsent() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        if (pim != null) pim.grantConsent();
    }


    /**
     * For use from whitelisted publishers only. Denies consent to collect personally identifiable
     * information for the current user.
     */
    public static void revokeConsent() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        if (pim != null) pim.revokeConsent();
    }


    @Nullable
    public String getCurrentVendorListVersion() {
        return getNullableConsentData().getCurrentVendorListVersion();
    }


    @Nullable
    public String getCurrentPrivacyPolicyVersion() {
        return getNullableConsentData().getCurrentPrivacyPolicyVersion();
    }


    @Nullable
    public String getCurrentVendorListIabFormat() {
        return getNullableConsentData().getCurrentVendorListIabFormat();
    }


    @Nullable
    public String getConsentedPrivacyPolicyVersion() {
        return getNullableConsentData().getConsentedPrivacyPolicyVersion();
    }


    @Nullable
    public String getConsentedVendorListVersion() {
        return getNullableConsentData().getConsentedVendorListVersion();
    }


    @Nullable
    public String getConsentedVendorListIabFormat() {
        return getNullableConsentData().getConsentedVendorListIabFormat();
    }



    /* ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** *****
     * Helper Methods                                                                          *
     * ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** ***** *****/

    public static String getSDKVersion() {
        return MoPub.SDK_VERSION;
    }

    protected static Activity getActivity() {
        return UnityPlayer.currentActivity;
    }

    protected static void runSafelyOnUiThread(final Runnable runner) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    runner.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    protected static void printExceptionStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        Log.e(TAG, sw.toString());
    }

    @NonNull
    protected static List<Class<? extends MoPubAdvancedBidder>> extractAdvancedBiddersClasses(
            String advancedBiddersString) {
        // Extract classes from advanced bidder strings
        final List<Class<? extends MoPubAdvancedBidder>> advancedBidders = new LinkedList<>();
        if (!TextUtils.isEmpty(advancedBiddersString)) {
            String[] advancedBiddersArray = advancedBiddersString.split(",");
            for (String advancedBidderString : advancedBiddersArray) {
                try {
                    Class<? extends MoPubAdvancedBidder> advancedBidderClass =
                            Class.forName(advancedBidderString).asSubclass(MoPubAdvancedBidder.class);
                    advancedBidders.add(advancedBidderClass);
                } catch (ClassNotFoundException e) {
                    Log.w(TAG, "Class not found for attempted network adapter class name: "
                            + advancedBidderString);
                }
            }
        }
        return advancedBidders;
    }

    protected static MediationSettings[] extractMediationSettingsFromJson(String json, boolean isInstance) {
        if (TextUtils.isEmpty(json))
            return new MediationSettings[0];

        ArrayList<MediationSettings> settings = new ArrayList<MediationSettings>();

        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObj = jsonArray.getJSONObject(i);
                String adVendor = jsonObj.getString("adVendor");
                Log.i(TAG, "adding MediationSettings for ad vendor: " + adVendor);
                if (adVendor.equalsIgnoreCase("chartboost")) {
                    if (jsonObj.has("customId")) {
                        try {
                            Class<?> mediationSettingsClass =
                                    Class.forName(CHARTBOOST_MEDIATION_SETTINGS);
                            Constructor<?> mediationSettingsConstructor =
                                    mediationSettingsClass.getConstructor(String.class);
                            MediationSettings s =
                                    (MediationSettings) mediationSettingsConstructor
                                            .newInstance(jsonObj.getString("customId"));
                            settings.add(s);
                        } catch (ClassNotFoundException e) {
                            Log.i(TAG, "could not find ChartboostMediationSettings class. " +
                                    "Did you add Chartboost Network SDK to your Android folder?");
                        } catch (Exception e) {
                            printExceptionStackTrace(e);
                        }
                    } else {
                        Log.i(TAG, "No customId key found in the settings object. " +
                                "Aborting adding Chartboost MediationSettings");
                    }
                } else if (adVendor.equalsIgnoreCase("vungle")) {
                    try {
                        Class<?> builderClass = Class.forName(VUNGLE_MEDIATION_SETTINGS);
                        Constructor<?> builderConstructor = builderClass.getConstructor();
                        Object b = builderConstructor.newInstance();

                        Method withUserId =
                                builderClass.getDeclaredMethod("withUserId",
                                        String.class);
                        Method withCancelDialogBody =
                                builderClass.getDeclaredMethod("withCancelDialogBody",
                                        String.class);
                        Method withCancelDialogCloseButton =
                                builderClass.getDeclaredMethod("withCancelDialogCloseButton",
                                        String.class);
                        Method withCancelDialogKeepWatchingButton =
                                builderClass.getDeclaredMethod("withCancelDialogKeepWatchingButton",
                                        String.class);
                        Method withCancelDialogTitle =
                                builderClass.getDeclaredMethod("withCancelDialogTitle",
                                        String.class);
                        Method build = builderClass.getDeclaredMethod("build");

                        if (jsonObj.has("userId")) {
                            withUserId.invoke(b, jsonObj.getString("userId"));
                        }

                        if (jsonObj.has("cancelDialogBody")) {
                            withCancelDialogBody.invoke(b, jsonObj.getString("cancelDialogBody"));
                        }

                        if (jsonObj.has("cancelDialogCloseButton")) {
                            withCancelDialogCloseButton
                                    .invoke(b, jsonObj.getString("cancelDialogCloseButton"));
                        }

                        if (jsonObj.has("cancelDialogKeepWatchingButton")) {
                            withCancelDialogKeepWatchingButton
                                    .invoke(b, jsonObj.getString("cancelDialogKeepWatchingButton"));
                        }

                        if (jsonObj.has("cancelDialogTitle")) {
                            withCancelDialogTitle.invoke(b, jsonObj.getString("cancelDialogTitle"));
                        }

                        settings.add((MediationSettings) build.invoke(b));

                    } catch (ClassNotFoundException e) {
                        Log.i(TAG, "could not find VungleMediationSettings class. " +
                                "Did you add Vungle Network SDK to your Android folder?");
                    } catch (Exception e) {
                        printExceptionStackTrace(e);
                    }
                } else if (adVendor.equalsIgnoreCase("adcolony")) {
                    if (jsonObj.has("withConfirmationDialog") && jsonObj.has("withResultsDialog")) {
                        boolean withConfirmationDialog =
                                jsonObj.getBoolean("withConfirmationDialog");
                        boolean withResultsDialog =
                                jsonObj.getBoolean("withResultsDialog");

                        try {
                            Class<?> mediationSettingsClass =
                                    Class.forName(isInstance ? ADCOLONY_INSTANCE_MEDIATION_SETTINGS
                                                             : ADCOLONY_GLOBAL_MEDIATION_SETTINGS);
                            Constructor<?> mediationSettingsConstructor =
                                    mediationSettingsClass
                                            .getConstructor(boolean.class, boolean.class);
                            MediationSettings s =
                                    (MediationSettings) mediationSettingsConstructor
                                            .newInstance(withConfirmationDialog, withResultsDialog);
                            settings.add(s);
                        } catch (ClassNotFoundException e) {
                            Log.i(TAG, "could not find AdColonyInstanceMediationSettings class. " +
                                    "Did you add AdColony Network SDK to your Android folder?");
                        } catch (Exception e) {
                            printExceptionStackTrace(e);
                        }
                    }
                } else if (adVendor.equalsIgnoreCase("googleplayservices")) {
                    if (jsonObj.has("npa")) {
                        try {
                            Class<?> mediationSettingsClass =
                                    Class.forName(ADMOB_MEDIATION_SETTINGS);
                            Constructor<?> constructor = mediationSettingsClass.getConstructor(Bundle.class);
                            Bundle extras = new Bundle();
                            extras.putString("npa", jsonObj.getString("npa"));
                            MediationSettings s = (MediationSettings) constructor.newInstance(extras);
                            settings.add(s);
                        } catch (ClassNotFoundException e) {
                            Log.i(TAG, "could not find GooglePlayServicesMediationSettings class. " +
                                    "Did you add AdMob Network SDK to your Android folder?");
                        } catch (Exception e) {
                            printExceptionStackTrace(e);
                        }
                    }
                 } else {
                    Log.e(TAG, "adVendor not available for custom mediation settings: " +
                            "[" + adVendor + "]");
                }
            }
        } catch (JSONException e) {
            printExceptionStackTrace(e);
        }

        return settings.toArray(new MediationSettings[settings.size()]);
    }

    private static final ConsentData NULL_CONSENT_DATA = new NullConsentData();

    @NonNull
    private static ConsentData getNullableConsentData() {
        PersonalInfoManager pim = MoPub.getPersonalInformationManager();
        return pim != null ? pim.getConsentData() : NULL_CONSENT_DATA;
    }

    /**
     * @noinspection ALL
     */
    private static class NullConsentData implements ConsentData {

        @Override
        public String getCurrentVendorListVersion() {
            return null;
        }

        @Override
        public String getCurrentVendorListLink() {
            return null;
        }

        @Override
        public String getCurrentVendorListLink(String language) {
            return null;
        }

        @Override
        public String getCurrentPrivacyPolicyVersion() {
            return null;
        }

        @Override
        public String getCurrentPrivacyPolicyLink() {
            return null;
        }

        @Override
        public String getCurrentPrivacyPolicyLink(@Nullable String language) {
            return null;
        }

        @Override
        public String getCurrentVendorListIabFormat() {
            return null;
        }

        @Override
        public String getConsentedPrivacyPolicyVersion() {
            return null;
        }

        @Override
        public String getConsentedVendorListVersion() {
            return null;
        }

        @Override
        public String getConsentedVendorListIabFormat() {
            return null;
        }

        @Deprecated
        @Override
        public boolean isForceGdprApplies() {
            return false;
        }
    }
}
