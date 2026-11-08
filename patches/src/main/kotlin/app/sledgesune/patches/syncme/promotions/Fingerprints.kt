package app.sledgesune.patches.syncme.promotions

import app.morphe.patcher.Fingerprint

// These classes keep their real names in the release build, so they can be matched directly.

/** Sale / campaign headers ("50% off Premium", special-offer pushes). */
object GetActivePromotionFingerprint : Fingerprint(
    definingClass = "Lcom/syncme/billing/promotional_header/PromotionalHeaderManager;",
    name = "getActivePromotion",
    returnType = "Lcom/syncme/billing/promotional_header/CampaignConfig;",
)

/** Premium bottom sheet shown when closing the after-SMS screen. */
object ShouldShowAfterSmsPromoFingerprint : Fingerprint(
    definingClass = "Lcom/syncme/caller_id/sms/after_sms/domain/AfterSmsPromoPolicy;",
    name = "shouldShowPromoOnClose",
    returnType = "Z",
    parameters = listOf("Z"),
)

/** Background worker that posts Premium promo notifications (backup, full report, trial ended). */
object PromoNotificationWorkerFingerprint : Fingerprint(
    definingClass = "Lcom/syncme/job_task/PromoNotificationWorker;",
    name = "execute",
    returnType = "Landroidx/work/ListenableWorker\$Result;",
    parameters = listOf(),
)

/** Picks the banner shown at the top of the call history. */
object GetNextPromoBannerFingerprint : Fingerprint(
    definingClass = "Lcom/syncme/activities/main_activity/fragment_history/promo_banner/PromoBannerUtil;",
    name = "getNextPromoBanner",
)

/** "Get the Sync.AI app" banners in the drawer, lists and after calls. */
object PrepareAppPromotionIntentFingerprint : Fingerprint(
    definingClass = "Lcom/syncme/ads/app_promotion/AppPromotionManager\$Companion;",
    name = "prepareIntentForAppBannerAd",
    returnType = "Landroid/content/Intent;",
)

/** "Free gift" card on the after-call screen. */
object FreeGiftIsToShowFingerprint : Fingerprint(
    definingClass = "Lcom/syncme/caller_id/FreeGiftChecker;",
    name = "isToShow",
    returnType = "Z",
    parameters = listOf(),
)
