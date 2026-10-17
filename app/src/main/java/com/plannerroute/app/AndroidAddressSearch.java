package com.plannerroute.app;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;

/** Device address provider. Call search from a worker, never the UI thread. */
final class AndroidAddressSearch implements Closeable {
    private final Geocoder geocoder;
    // A stuck legacy provider must not cause another thread to be created on each retry.
    private final ThreadPoolExecutor legacy = new ThreadPoolExecutor(1, 1, 0L,
        TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "plannerroute-geocoder");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    AndroidAddressSearch(Context context) {
        geocoder = new Geocoder(context.getApplicationContext(), Locale.KOREA);
    }

    List<Data.Place> search(String query) throws IOException {
        if (closed) throw new IOException("주소 검색이 종료되었습니다.");
        String term = query == null ? "" : query.trim();
        if (term.isEmpty()) return Collections.emptyList();
        if (!Geocoder.isPresent()) throw new IOException("이 휴대폰에서 기본 주소 검색을 사용할 수 없습니다.");
        Future<List<Address>> request;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                CompletableFuture<List<Address>> future = new CompletableFuture<>();
                request = future;
                pending.add(request);
                if (closed) future.cancel(true);
                else Api33.search(geocoder, term, future);
            } else {
                request = legacy.submit(() -> legacySearch(term));
                pending.add(request);
                if (closed) request.cancel(true);
            }
        } catch (RuntimeException e) {
            // Also covers a legacy provider still occupying the single worker.
            throw new IOException("주소 검색 서비스가 사용 중이거나 응답하지 않습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        try {
            return places(request.get(15, TimeUnit.SECONDS), term);
        } catch (TimeoutException e) {
            request.cancel(true);
            throw new IOException("주소 검색 시간이 초과되었습니다. 다른 검색어나 지도 선택을 이용해 주세요.", e);
        } catch (InterruptedException e) {
            request.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("주소 검색이 취소되었습니다.", e);
        } catch (CancellationException e) {
            throw new IOException("주소 검색이 취소되었습니다.", e);
        } catch (ExecutionException e) {
            throw new IOException("휴대폰 주소 검색 서비스에 연결하지 못했습니다.", e.getCause());
        } finally {
            pending.remove(request);
        }
    }

    @SuppressWarnings("deprecation")
    private List<Address> legacySearch(String query) throws IOException {
        return geocoder.getFromLocationName(query, 5);
    }

    // Isolate API 33 classes so older Android versions never instantiate a listener.
    private static final class Api33 {
        static void search(Geocoder geocoder, String query, CompletableFuture<List<Address>> future) {
            try {
                geocoder.getFromLocationName(query, 5, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(List<Address> addresses) {
                        future.complete(addresses);
                    }
                    @Override public void onError(String message) {
                        future.completeExceptionally(new IOException("기본 주소 검색 서버가 응답하지 않았습니다."));
                    }
                });
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }
    }

    private static List<Data.Place> places(List<Address> addresses, String query) {
        List<Data.Place> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (addresses == null) return result;
        for (Address address : addresses) {
            if (address == null || !address.hasLatitude() || !address.hasLongitude()) continue;
            double lat = address.getLatitude(), lon = address.getLongitude();
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 85 || Math.abs(lon) > 180) continue;
            if (!seen.add(lat + "," + lon)) continue;
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                String line = address.getAddressLine(i);
                if (line != null && !line.trim().isEmpty()) {
                    if (lines.length() > 0) lines.append(' ');
                    lines.append(line.trim());
                }
            }
            String name = address.getFeatureName();
            if (name == null || name.trim().isEmpty()) name = query;
            name = name.trim();
            if (name.length() > 200) {
                int end = Character.isHighSurrogate(name.charAt(199)) ? 199 : 200;
                name = name.substring(0, end);
            }
            Data.Place place = new Data.Place(name, lat, lon);
            place.address = lines.length() == 0 ? query : lines.toString();
            result.add(place);
            if (result.size() == 5) break;
        }
        return result;
    }

    @Override public void close() {
        closed = true;
        for (Future<?> request : pending) request.cancel(true);
        pending.clear();
        legacy.shutdownNow();
    }
}
