package app.sledgesune.extension.waze;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trip history for Waze: how long each drive took and how far it went.
 * <p>
 * Drives are recorded on the phone from navigation start to arrival (or stop). The distance is the GPS track
 * driven; if no GPS fixes arrive, the drop in remaining route distance is used instead. The newest drive to a place
 * is shown on the right of that place's row under "Where to?" › Recent. The side menu gets a "Trip history" screen
 * listing every drive, merged with the drive list from waze.com when that can be loaded (phone records win).
 * <p>
 * Waze's classes are obfuscated and not on the compile classpath, so they are reached by reflection. Names the
 * patch can only find at patch time are written into the stub methods below.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class TripHistoryPatch {

    private static final String TAG = "TripHistoryPatch";

    private static final String PREFS = "sledgesune_trips_v2";
    private static final String OLD_PREFS = "sledgesune_trip_history";
    private static final String KEY_TRIPS = "trips";
    private static final String KEY_OPEN = "open";
    private static final int MAX_TRIPS = 2000;
    private static final long SAVE_INTERVAL_MS = 60_000;

    /** Shorter or shorter-distance "drives" are route previews or false starts, not drives. */
    private static final long MIN_TRIP_MS = 60_000;
    private static final int MIN_TRIP_METERS = 200;

    /** Two places are the same if both coordinates are this close (micro-degrees, about 150 m). */
    private static final int SAME_PLACE_MICRODEGREES = 1500;

    private static final String WAZE_WEB = "https://www.waze.com";
    /** Server environments: rest of world, North America, Israel. An account has drives in one of them. */
    private static final String[] ENVIRONMENTS = {"row-", "", "il-"};
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 40;

    private static volatile Application application;
    private static volatile WeakReference<Activity> currentActivity = new WeakReference<>(null);

    // region Patch-time names (the patch replaces these bodies)

    public static String webHeadersClass() {
        return "";
    }

    public static String webHeadersMethod() {
        return "";
    }

    public static String trailingTextField() {
        return "";
    }

    public static String secondaryTrailingTextField() {
        return "";
    }

    public static String plainTextClass() {
        return "";
    }

    public static String textClass() {
        return "";
    }

    public static String onClickInterface() {
        return "";
    }

    // endregion

    // region Data

    private static final class Trip implements Comparable<Trip> {
        long start;
        long end;
        /** Negative when unknown. */
        int meters = -1;
        String name;
        String venueId;
        /** Micro-degrees; Integer.MIN_VALUE when unknown. */
        int lat = Integer.MIN_VALUE;
        int lon = Integer.MIN_VALUE;
        /** The app was closed mid-drive; the end is the last moment it was seen running. */
        boolean endEstimated;
        boolean online;

        @Override
        public int compareTo(Trip other) {
            return Long.compare(other.start, start); // Newest first.
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("start", start).put("end", end).put("m", meters).put("est", endEstimated);
            if (name != null) json.put("name", name);
            if (venueId != null) json.put("venue", venueId);
            if (lat != Integer.MIN_VALUE) json.put("lat", lat).put("lon", lon);
            return json;
        }

        static Trip fromJson(JSONObject json) {
            Trip trip = new Trip();
            trip.start = json.optLong("start");
            trip.end = json.optLong("end");
            trip.meters = json.optInt("m", -1);
            trip.endEstimated = json.optBoolean("est");
            trip.name = json.has("name") ? json.optString("name") : null;
            trip.venueId = json.has("venue") ? json.optString("venue") : null;
            if (json.has("lat")) {
                trip.lat = json.optInt("lat");
                trip.lon = json.optInt("lon");
            }
            return trip;
        }
    }

    /** The drive in progress. Saved to prefs so a drive survives the app being killed. */
    private static final class OpenTrip {
        long start;
        long lastSeen;
        long arrivedAt;
        double gpsMeters;
        int firstRemaining = -1;
        int lastRemaining = -1;
        Trip destination = new Trip();

        JSONObject toJson() throws Exception {
            return new JSONObject().put("start", start).put("seen", lastSeen).put("arrived", arrivedAt)
                    .put("gps", gpsMeters).put("rem0", firstRemaining).put("rem1", lastRemaining)
                    .put("dest", destination.toJson());
        }

        static OpenTrip fromJson(JSONObject json) {
            OpenTrip open = new OpenTrip();
            open.start = json.optLong("start");
            open.lastSeen = json.optLong("seen");
            open.arrivedAt = json.optLong("arrived");
            open.gpsMeters = json.optDouble("gps", 0);
            open.firstRemaining = json.optInt("rem0", -1);
            open.lastRemaining = json.optInt("rem1", -1);
            JSONObject destination = json.optJSONObject("dest");
            if (destination != null) open.destination = Trip.fromJson(destination);
            return open;
        }
    }

    private static final Object lock = new Object();
    private static OpenTrip openTrip;
    /** Destination of the current route; copied into the trip when navigation starts and whenever it changes. */
    private static Trip currentDestination;
    private static List<Trip> tripsCache;
    private static long lastSave;

    // endregion

    // region Injection points: recording

    /** Injection point: Application.onCreate(). */
    public static void onApplicationCreate(Application app) {
        try {
            application = app;
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    currentActivity = new WeakReference<>(activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    if (currentActivity.get() == activity) currentActivity = new WeakReference<>(null);
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });

            // Data from the first version of this patch is not kept.
            app.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE).edit().clear().apply();

            // The app was closed during a drive: end it at the last moment it was seen running.
            synchronized (lock) {
                String saved = prefs().getString(KEY_OPEN, null);
                if (saved != null) {
                    OpenTrip open = OpenTrip.fromJson(new JSONObject(saved));
                    long end = open.arrivedAt > 0 ? open.arrivedAt : Math.max(open.lastSeen, open.start);
                    finishTrip(open, end, open.arrivedAt == 0);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onApplicationCreate failed", t);
        }
    }

    /** Injection point: the native core reports that navigation started or stopped. */
    public static void onNavigationStateChanged(boolean navigating, int reason) {
        try {
            synchronized (lock) {
                long now = System.currentTimeMillis();
                if (navigating) {
                    if (openTrip != null) return;
                    openTrip = new OpenTrip();
                    openTrip.start = now;
                    openTrip.lastSeen = now;
                    if (currentDestination != null) openTrip.destination = currentDestination;
                    saveOpenTrip();
                    startGps();
                } else if (openTrip != null) {
                    OpenTrip open = openTrip;
                    openTrip = null;
                    stopGps();
                    finishTrip(open, open.arrivedAt > 0 ? open.arrivedAt : now, false);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onNavigationStateChanged failed", t);
        }
    }

    /** Injection point: called often while navigating. */
    public static void onNavigationHeartbeat(int etaSeconds) {
        try {
            synchronized (lock) {
                if (openTrip == null) return;
                long now = System.currentTimeMillis();
                openTrip.lastSeen = now;
                if (now - lastSave >= SAVE_INTERVAL_MS) saveOpenTrip();
            }
        } catch (Throwable t) {
            Log.e(TAG, "onNavigationHeartbeat failed", t);
        }
    }

    /** Injection point: remaining distance to the destination (DistanceUpdate protobuf). */
    public static void onRemainingDistance(byte[] distanceUpdate) {
        try {
            Object update = Class.forName("com.waze.jni.protos.navigate.DistanceUpdate")
                    .getMethod("parseFrom", byte[].class).invoke(null, (Object) distanceUpdate);
            int meters = (Integer) update.getClass().getMethod("getRawMeters").invoke(update);
            synchronized (lock) {
                if (openTrip == null || meters < 0) return;
                if (openTrip.firstRemaining < 0) openTrip.firstRemaining = meters;
                openTrip.lastRemaining = meters;
            }
        } catch (Throwable t) {
            Log.w(TAG, "onRemainingDistance failed", t);
        }
    }

    /** Injection point: the destination of the current route changed (GenericPlace protobuf). */
    public static void onDestinationChanged(byte[] genericPlace) {
        try {
            Object place = Class.forName("com.waze.jni.protos.places.GenericPlace")
                    .getMethod("parseFrom", byte[].class).invoke(null, (Object) genericPlace);
            Trip destination = new Trip();

            Object address = call(place, "getWazeAddress");
            if (address != null) {
                destination.name = emptyToNull((String) call(address, "getName"));
                destination.venueId = emptyToNull((String) call(address, "getVenueId"));
                Object position = call(address, "getPosition");
                if (position != null) {
                    int lat = (Integer) call(position, "getLatitude");
                    int lon = (Integer) call(position, "getLongitude");
                    if (lat != 0 || lon != 0) {
                        destination.lat = lat;
                        destination.lon = lon;
                    }
                }
            }
            for (String getter : new String[]{"getRecentPlace", "getFavoritePlace", "getEventPlace"}) {
                if (destination.name != null) break;
                Object named = call(place, getter);
                if (named != null) destination.name = emptyToNull((String) call(named, "getName"));
            }

            synchronized (lock) {
                currentDestination = destination;
                if (openTrip != null) {
                    openTrip.destination = destination;
                    saveOpenTrip();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onDestinationChanged failed", t);
        }
    }

    /** Injection point: arrived at the destination. The drive ends now, even if navigation stops later. */
    public static void onArrived() {
        try {
            synchronized (lock) {
                if (openTrip == null || openTrip.arrivedAt > 0) return;
                openTrip.arrivedAt = System.currentTimeMillis();
                saveOpenTrip();
            }
        } catch (Throwable t) {
            Log.e(TAG, "onArrived failed", t);
        }
    }

    // endregion

    // region Recording helpers

    private static SharedPreferences prefs() {
        return application.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void saveOpenTrip() {
        try {
            lastSave = System.currentTimeMillis();
            prefs().edit().putString(KEY_OPEN, openTrip.toJson().toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "Could not save the drive in progress", t);
        }
    }

    /** Called with {@link #lock} held. Saves the drive if it was a real one, and clears the drive in progress. */
    private static void finishTrip(OpenTrip open, long end, boolean endEstimated) throws Exception {
        Trip trip = new Trip();
        trip.name = open.destination.name;
        trip.venueId = open.destination.venueId;
        trip.lat = open.destination.lat;
        trip.lon = open.destination.lon;
        trip.start = open.start;
        trip.end = end;
        trip.endEstimated = endEstimated;
        if (open.gpsMeters > 0) {
            trip.meters = (int) Math.round(open.gpsMeters);
        } else if (open.firstRemaining > 0 && open.lastRemaining >= 0 && open.firstRemaining > open.lastRemaining) {
            trip.meters = open.firstRemaining - open.lastRemaining;
        } else {
            trip.meters = -1;
        }

        SharedPreferences.Editor editor = prefs().edit().remove(KEY_OPEN);
        boolean tooShort = trip.end - trip.start < MIN_TRIP_MS || (trip.meters >= 0 && trip.meters < MIN_TRIP_METERS);
        if (!tooShort) {
            List<Trip> trips = new ArrayList<>(loadTrips());
            trips.add(trip);
            Collections.sort(trips);
            while (trips.size() > MAX_TRIPS) trips.remove(trips.size() - 1);
            JSONArray array = new JSONArray();
            for (Trip saved : trips) array.put(saved.toJson());
            editor.putString(KEY_TRIPS, array.toString());
            tripsCache = trips;
        }
        editor.apply();
    }

    /** Phone-recorded trips, newest first. */
    private static List<Trip> loadTrips() {
        synchronized (lock) {
            if (tripsCache != null) return tripsCache;
            List<Trip> trips = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(prefs().getString(KEY_TRIPS, "[]"));
                for (int i = 0; i < array.length(); i++) trips.add(Trip.fromJson(array.getJSONObject(i)));
            } catch (Throwable t) {
                Log.w(TAG, "Could not read saved trips", t);
            }
            Collections.sort(trips);
            tripsCache = trips;
            return trips;
        }
    }

    // GPS track: summed while navigating. Waze already holds the location permission and keeps GPS on.

    private static LocationListener gpsListener;
    private static Location lastFix;

    private static void startGps() {
        final Application app = application;
        if (app == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (gpsListener != null) return;
                if (Build.VERSION.SDK_INT >= 23 && app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) return;
                LocationManager manager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
                if (manager == null) return;
                lastFix = null;
                gpsListener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        onFix(location);
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {
                    }

                    @Override
                    public void onProviderEnabled(String provider) {
                    }

                    @Override
                    public void onProviderDisabled(String provider) {
                    }
                };
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, Looper.getMainLooper());
            } catch (Throwable t) {
                Log.w(TAG, "Could not start GPS tracking", t);
                gpsListener = null;
            }
        });
    }

    private static void stopGps() {
        final Application app = application;
        if (app == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (gpsListener == null) return;
                LocationManager manager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
                if (manager != null) manager.removeUpdates(gpsListener);
            } catch (Throwable t) {
                Log.w(TAG, "Could not stop GPS tracking", t);
            } finally {
                gpsListener = null;
                lastFix = null;
            }
        });
    }

    private static void onFix(Location fix) {
        try {
            if (fix.hasAccuracy() && fix.getAccuracy() > 40) return;
            Location previous = lastFix;
            if (previous == null) {
                lastFix = fix;
                return;
            }
            float meters = previous.distanceTo(fix);
            double seconds = Math.max(1, (fix.getTime() - previous.getTime()) / 1000.0);
            if (meters < 5) return; // Standing still: GPS noise. Keep the previous fix.
            lastFix = fix;
            if (meters / seconds > 70) return; // Faster than 250 km/h: a jump, not driving.
            synchronized (lock) {
                if (openTrip != null && openTrip.arrivedAt == 0) openTrip.gpsMeters += meters;
            }
        } catch (Throwable t) {
            Log.w(TAG, "onFix failed", t);
        }
    }

    // endregion

    // region Injection point: Recent rows

    /**
     * Injection point: a DestinationCell was built for a place in the Recent section.
     * Shows the newest drive to that place on the right of the row: duration on top, distance below.
     *
     * @param cell the DestinationCell
     * @param item the GenericPlaceListItem it was built from
     */
    public static void decorateRecentCell(Object cell, Object item) {
        try {
            Object addressItem = findAddressItem(item);
            if (addressItem == null) return;
            Trip trip = newestTripTo(
                    (String) call(addressItem, "getTitle"),
                    (String) call(addressItem, "getVenueId"),
                    (Integer) call(addressItem, "getLatitudeInt"),
                    (Integer) call(addressItem, "getLongitudeInt"));
            if (trip == null) return;

            Field trailing = findField(cell.getClass(), trailingTextField());
            Field secondary = findField(cell.getClass(), secondaryTrailingTextField());
            Class<?> textType = Class.forName(textClass());
            Constructor<?> text = textType.getConstructor(String.class);

            if (trailing.get(cell) == null) {
                Class<?> plainType = Class.forName(plainTextClass());
                Constructor<?> plain = plainType.getConstructor(textType.getSuperclass());
                trailing.set(cell, plain.newInstance(text.newInstance(formatDuration(trip.end - trip.start))));
            }
            if (trip.meters >= 0 && secondary.get(cell) == null) {
                secondary.set(cell, text.newInstance(formatKm(trip.meters)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "decorateRecentCell failed", t);
        }
    }

    /** The item holds a place object, which holds the AddressItem (the only class here with a real name). */
    private static Object findAddressItem(Object item) throws Exception {
        for (Class<?> type = item.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                Object place = field.get(item);
                if (place == null) continue;
                for (Class<?> placeType = place.getClass(); placeType != null && placeType != Object.class;
                     placeType = placeType.getSuperclass()) {
                    for (Method method : placeType.getDeclaredMethods()) {
                        if (method.getParameterTypes().length == 0
                                && method.getReturnType().getName().equals("com.waze.place.data.AddressItem")) {
                            method.setAccessible(true);
                            return method.invoke(place);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Trip newestTripTo(String title, String venueId, int lat, int lon) {
        for (Trip trip : loadTrips()) {
            if (venueId != null && !venueId.isEmpty() && venueId.equals(trip.venueId)) return trip;
            if ((lat != 0 || lon != 0) && trip.lat != Integer.MIN_VALUE
                    && Math.abs(trip.lat - lat) <= SAME_PLACE_MICRODEGREES
                    && Math.abs(trip.lon - lon) <= SAME_PLACE_MICRODEGREES) return trip;
            if (title != null && !title.isEmpty() && title.equalsIgnoreCase(trip.name)) return trip;
        }
        return null;
    }

    // endregion

    // region Injection points: side menu

    private static Object menuOnClick;

    /** Injection point: title of the side menu row. */
    public static String menuTitle() {
        return "Trip history";
    }

    /** Injection point: icon of the side menu row (Waze's own history icon). */
    public static int menuIcon() {
        try {
            Application app = application;
            if (app != null) {
                int id = app.getResources().getIdentifier("history_outline_24px", "drawable", app.getPackageName());
                if (id != 0) return id;
            }
        } catch (Throwable t) {
            Log.w(TAG, "menuIcon failed", t);
        }
        return android.R.drawable.ic_menu_recent_history;
    }

    /** Injection point: click action of the side menu row, an instance of the app's Kotlin Function0. */
    public static Object menuOnClick() {
        try {
            if (menuOnClick == null) {
                Class<?> function = Class.forName(onClickInterface());
                menuOnClick = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "equals":
                                    return args != null && args.length == 1 && args[0] == proxy;
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "TripHistoryMenuClick";
                                default:
                                    openTripList();
                                    return null;
                            }
                        });
            }
            return menuOnClick;
        } catch (Throwable t) {
            Log.e(TAG, "menuOnClick failed", t);
            return null;
        }
    }

    private static void openTripList() {
        try {
            Activity activity = currentActivity.get();
            if (activity == null || activity.isFinishing()) return;
            new TripListScreen(activity).show();
        } catch (Throwable t) {
            Log.e(TAG, "Could not open the trip list", t);
        }
    }

    // endregion

    // region waze.com drive list

    private static final class OnlineResult {
        final List<Trip> trips = new ArrayList<>();
        boolean signedIn;
        boolean failed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> webHeaders() {
        try {
            Method method = Class.forName(webHeadersClass()).getDeclaredMethod(webHeadersMethod(), int.class, int.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(null, 0, 0);
        } catch (Throwable t) {
            Log.w(TAG, "Could not get the app's web headers", t);
            return new HashMap<>();
        }
    }

    /** Address of Waze's own Navigation history page (server config). */
    private static String historyPageUrl() {
        try {
            Object config = Class.forName("com.waze.config.ConfigValues")
                    .getField("CONFIG_VALUE_PRIVACY_DRIVE_HISTORY_URL").get(null);
            for (Class<?> type = config.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 0 || method.getReturnType().isPrimitive()) continue;
                    method.setAccessible(true);
                    Object value = method.invoke(config);
                    if (value instanceof String && ((String) value).startsWith("http")) return (String) value;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not read the navigation history URL", t);
        }
        return null;
    }

    /**
     * Opens Waze's own Navigation history page off-screen with the app's headers, the way the app does, so
     * waze.com sets its session cookies. Then calls back on the UI thread (also after a timeout).
     */
    private static void openWebSession(Activity activity, Map<String, String> headers, Runnable then) {
        String url = historyPageUrl();
        if (url == null) {
            then.run();
            return;
        }
        final AtomicBoolean done = new AtomicBoolean();
        final WebView webView = new WebView(activity);
        final Runnable finish = () -> {
            if (!done.compareAndSet(false, true)) return;
            CookieManager.getInstance().flush();
            webView.destroy();
            then.run();
        };
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                // Give the page's own scripts a moment to finish signing in.
                new Handler(Looper.getMainLooper()).postDelayed(finish, 1500);
            }
        });
        webView.loadUrl(url, headers);
        new Handler(Looper.getMainLooper()).postDelayed(finish, 15_000);
    }

    private static OnlineResult fetchOnlineTrips(Map<String, String> headers) {
        OnlineResult result = new OnlineResult();
        String cookies = null;
        String userAgent = null;
        try {
            cookies = CookieManager.getInstance().getCookie(WAZE_WEB);
            userAgent = WebSettings.getDefaultUserAgent(application);
        } catch (Throwable t) {
            Log.w(TAG, "WebView not available", t);
        }

        for (String environment : ENVIRONMENTS) {
            try {
                for (int page = 0; page < MAX_PAGES; page++) {
                    String url = WAZE_WEB + "/" + environment + "Descartes/app/Archive/List?minDistance=0&offset="
                            + (page * PAGE_SIZE) + "&count=" + PAGE_SIZE;
                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(15_000);
                    connection.setReadTimeout(20_000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Accept", "application/json");
                    if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
                    if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        if (header.getKey() != null && header.getValue() != null) {
                            connection.setRequestProperty(header.getKey(), header.getValue());
                        }
                    }

                    int status = connection.getResponseCode();
                    if (status != 200) {
                        Log.i(TAG, "Drive list " + environment + ": HTTP " + status);
                        connection.disconnect();
                        break; // 403 = not signed in on this environment.
                    }
                    result.signedIn = true;

                    JSONArray objects = new JSONObject(readAll(connection.getInputStream()))
                            .getJSONObject("archives").getJSONArray("objects");
                    connection.disconnect();

                    for (int i = 0; i < objects.length(); i++) {
                        JSONObject drive = objects.getJSONObject(i);
                        Trip trip = new Trip();
                        trip.start = drive.optLong("startTime", 0);
                        trip.end = drive.optLong("endTime", 0);
                        trip.meters = drive.optInt("totalRoadMeters", -1);
                        trip.online = true;
                        // Entries without an end time are app launches where no drive happened.
                        if (trip.start > 0 && trip.end > trip.start) result.trips.add(trip);
                    }
                    if (objects.length() < PAGE_SIZE) break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Drive list request failed for environment '" + environment + "'", t);
                result.failed = true;
            }
        }
        return result;
    }

    private static String readAll(InputStream stream) throws Exception {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    /** Phone trips first; waze.com drives are added only where no phone trip overlaps them. */
    private static List<Trip> merge(List<Trip> local, List<Trip> online) {
        List<Trip> merged = new ArrayList<>(local);
        for (Trip trip : online) {
            boolean covered = false;
            for (Trip other : local) {
                if (trip.start < other.end && trip.end > other.start) {
                    covered = true;
                    break;
                }
            }
            if (!covered) merged.add(trip);
        }
        Collections.sort(merged);
        return merged;
    }

    // endregion

    // region Trip list screen

    private static final class TripListScreen {
        private final Activity activity;
        private final boolean night;
        private final int background;
        private final int primaryText;
        private final int secondaryText;
        private final int accent = Color.parseColor("#33CCFF");

        private final SimpleDateFormat dayFormat = new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat csvFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

        private Dialog dialog;
        private TextView status;
        private TripAdapter adapter;
        private List<Trip> shown = new ArrayList<>();

        TripListScreen(Activity activity) {
            this.activity = activity;
            night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            background = night ? Color.parseColor("#1F2023") : Color.WHITE;
            primaryText = night ? Color.WHITE : Color.parseColor("#1C1C1E");
            secondaryText = night ? Color.parseColor("#A6A9B0") : Color.parseColor("#5F6368");
        }

        private int dp(float value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    activity.getResources().getDisplayMetrics()));
        }

        private TextView text(float sizeSp, int color, boolean bold) {
            TextView view = new TextView(activity);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            view.setTextColor(color);
            if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
            return view;
        }

        void show() {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(background);

            // Header: back, title, Copy CSV.
            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(8), dp(12), dp(16), dp(12));

            TextView back = text(24, primaryText, false);
            back.setText("←");
            back.setPadding(dp(12), dp(4), dp(16), dp(4));
            back.setOnClickListener(v -> dialog.dismiss());
            header.addView(back);

            TextView title = text(20, primaryText, true);
            title.setText("Trip history");
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView copy = text(15, accent, true);
            copy.setText("Copy CSV");
            copy.setPadding(dp(8), dp(8), dp(8), dp(8));
            copy.setOnClickListener(v -> copyCsv());
            header.addView(copy);
            root.addView(header);

            status = text(14, secondaryText, false);
            status.setPadding(dp(20), 0, dp(20), dp(8));
            root.addView(status);

            ListView list = new ListView(activity);
            list.setDivider(null);
            adapter = new TripAdapter();
            list.setAdapter(adapter);
            root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            dialog = new Dialog(activity, night
                    ? android.R.style.Theme_DeviceDefault_NoActionBar
                    : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
            }
            dialog.show();

            render(loadTrips(), "Loading drives from waze.com…", false);
            loadOnline();
        }

        private void loadOnline() {
            final Map<String, String> headers = webHeaders();
            openWebSession(activity, headers, () -> new Thread(() -> {
                final OnlineResult online = fetchOnlineTrips(headers);
                activity.runOnUiThread(() -> {
                    if (dialog == null || !dialog.isShowing()) return;
                    List<Trip> local = loadTrips();
                    if (online.signedIn) {
                        render(merge(local, online.trips), null, false);
                    } else {
                        render(local, (online.failed ? "Could not reach waze.com." : "Not signed in to waze.com.")
                                + " Showing drives recorded on this phone. Tap here to sign in.", true);
                    }
                });
            }, "TripHistoryFetch").start());
        }

        private void render(List<Trip> trips, String notice, boolean signInOnTap) {
            shown = new ArrayList<>(trips);
            Collections.sort(shown);
            if (notice == null && shown.isEmpty()) notice = "No drives yet. Drives with a destination set are recorded.";
            status.setText(notice == null ? "" : notice);
            status.setVisibility(notice == null ? View.GONE : View.VISIBLE);
            status.setOnClickListener(signInOnTap ? v -> showSignIn() : null);
            adapter.notifyDataSetChanged();
        }

        private final class TripAdapter extends BaseAdapter {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int position) {
                return shown.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Trip trip = shown.get(position);
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(20), dp(12), dp(20), dp(12));

                TextView line1 = text(16, primaryText, true);
                line1.setText(trip.name != null ? trip.name : dayFormat.format(new Date(trip.start)));
                row.addView(line1);

                StringBuilder details = new StringBuilder();
                if (trip.name != null) details.append(dayFormat.format(new Date(trip.start))).append(" · ");
                details.append(timeFormat.format(new Date(trip.start))).append(" → ")
                        .append(trip.endEstimated ? "~" : "").append(timeFormat.format(new Date(trip.end)))
                        .append(" · ").append(formatDuration(trip.end - trip.start));
                if (trip.meters >= 0) details.append(" · ").append(formatKm(trip.meters));
                if (trip.online) details.append(" · waze.com");
                TextView line2 = text(14, secondaryText, false);
                line2.setText(details);
                row.addView(line2);
                return row;
            }
        }

        private void copyCsv() {
            StringBuilder csv = new StringBuilder("start,end,minutes,km,destination,source\n");
            for (Trip trip : shown) {
                String name = trip.name == null ? "" : '"' + trip.name.replace("\"", "\"\"") + '"';
                csv.append(csvFormat.format(new Date(trip.start))).append(',')
                        .append(csvFormat.format(new Date(trip.end))).append(',')
                        .append(Math.round((trip.end - trip.start) / 60000.0)).append(',')
                        .append(trip.meters >= 0 ? String.format(Locale.US, "%.1f", trip.meters / 1000.0) : "").append(',')
                        .append(name).append(',')
                        .append(trip.online ? "waze.com" : (trip.endEstimated ? "phone (end estimated)" : "phone"))
                        .append('\n');
            }
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Waze trip history", csv));
                Toast.makeText(activity, shown.size() + " drives copied", Toast.LENGTH_SHORT).show();
            }
        }

        /** waze.com sign-in inside a WebView. Its cookies are then used for the drive list. */
        private void showSignIn() {
            try {
                final WebView webView = new WebView(activity);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                CookieManager.getInstance().setAcceptCookie(true);
                webView.setWebViewClient(new WebViewClient());
                webView.loadUrl(WAZE_WEB + "/signin?redirect=%2Faccount");

                new AlertDialog.Builder(activity)
                        .setTitle("Sign in to waze.com")
                        .setView(webView)
                        .setPositiveButton("Done", null)
                        .setOnDismissListener(d -> {
                            CookieManager.getInstance().flush();
                            webView.destroy();
                            if (dialog != null && dialog.isShowing()) {
                                render(loadTrips(), "Loading drives from waze.com…", false);
                                loadOnline();
                            }
                        })
                        .show();
            } catch (Throwable t) {
                Log.e(TAG, "Could not open the sign-in page", t);
            }
        }
    }

    // endregion

    // region Utilities

    private static Object call(Object target, String method) throws Exception {
        if (target == null) return null;
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> t = type; t != null; t = t.getSuperclass()) {
            try {
                Field field = t.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static String formatDuration(long millis) {
        long minutes = Math.max(0, Math.round(millis / 60000.0));
        if (minutes < 60) return minutes + " min";
        return String.format(Locale.getDefault(), "%d h %02d min", minutes / 60, minutes % 60);
    }

    static String formatKm(int meters) {
        return String.format(Locale.getDefault(), "%.1f km", meters / 1000.0);
    }

    // endregion
}
