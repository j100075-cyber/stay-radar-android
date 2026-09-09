package com.stayradar.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private EditText apiKey, region, checkIn, checkOut, adults;
    private CheckBox flex;
    private Spinner lodgingType;
    private ProgressBar progress;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(17,24,39));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(40));
        scroll.addView(root);

        TextView title = text("숙박레이더", 28, true);
        root.addView(title);
        TextView sub = text("예약 가능한 숙소 · 최저가 · ±1일 비교", 14, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        apiKey = input("SerpApi API Key");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(getPreferences(MODE_PRIVATE).getString("api_key", ""));
        root.addView(label("처음 한 번만 API 키 입력"));
        root.addView(apiKey);

        Button save = button("API 키 저장");
        save.setOnClickListener(v -> {
            String key = apiKey.getText().toString().trim();
            getPreferences(MODE_PRIVATE).edit().putString("api_key", key).apply();
            toast("API 키가 저장되었습니다.");
        });
        root.addView(save);

        Button issue = button("SerpApi 가입 / 키 발급 페이지 열기");
        issue.setOnClickListener(v -> open("https://serpapi.com/users/sign_up"));
        root.addView(issue);

        region = input("예: 부산 해운대, 제주 애월, 경주");
        root.addView(label("지역"));
        root.addView(region);

        LocalDate ci = LocalDate.now().plusDays(7);
        LocalDate co = ci.plusDays(1);
        checkIn = input(ci.toString());
        checkOut = input(co.toString());
        checkIn.setFocusable(false);
        checkOut.setFocusable(false);
        checkIn.setOnClickListener(v -> pickDate(checkIn));
        checkOut.setOnClickListener(v -> pickDate(checkOut));

        root.addView(label("체크인"));
        root.addView(checkIn);
        root.addView(label("체크아웃"));
        root.addView(checkOut);

        adults = input("2");
        adults.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(label("성인 인원"));
        root.addView(adults);

        root.addView(label("숙소 유형"));
        lodgingType = new Spinner(this);
        String[] types = new String[]{"전체 (호텔 + 펜션·풀빌라)", "호텔", "펜션·풀빌라·독채"};
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, types);
        lodgingType.setAdapter(typeAdapter);
        root.addView(lodgingType);
        TextView typeNote = text("※ 전체 검색은 호텔과 펜션을 각각 조회합니다. ±1일까지 켜면 API 사용량이 늘어납니다.", 12, false);
        typeNote.setTextColor(Color.GRAY);
        root.addView(typeNote);

        flex = new CheckBox(this);
        flex.setText("±1일 최저가 비교");
        flex.setTextSize(15);
        root.addView(flex);

        Button search = button("🔎 숙박 최저가 검색");
        search.setTextSize(17);
        search.setOnClickListener(v -> search());
        root.addView(search);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        status = text("", 13, false);
        status.setTextColor(Color.DKGRAY);
        root.addView(status);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        setContentView(scroll);
    }

    private void search() {
        String key = apiKey.getText().toString().trim();
        String q = region.getText().toString().trim();
        if (key.isEmpty()) { toast("먼저 SerpApi API 키를 입력해주세요."); return; }
        if (q.isEmpty()) { toast("지역을 입력해주세요."); return; }

        LocalDate ci, co;
        try {
            ci = LocalDate.parse(checkIn.getText().toString());
            co = LocalDate.parse(checkOut.getText().toString());
        } catch (Exception e) {
            toast("날짜를 확인해주세요."); return;
        }
        long nights = ChronoUnit.DAYS.between(ci, co);
        if (nights <= 0) { toast("체크아웃은 체크인보다 뒤여야 합니다."); return; }

        int adultCount = 2;
        try { adultCount = Math.max(1, Integer.parseInt(adults.getText().toString())); }
        catch (Exception ignored) {}

        getPreferences(MODE_PRIVATE).edit().putString("api_key", key).apply();
        results.removeAllViews();
        progress.setVisibility(View.VISIBLE);
        status.setText("실시간 가격 확인 중…");

        final int a = adultCount;
        final LocalDate fci = ci;
        final LocalDate fco = co;
        final int typeMode = lodgingType.getSelectedItemPosition();
        executor.execute(() -> {
            try {
                List<Stay> all = new ArrayList<>();
                int[] offsets = flex.isChecked() ? new int[]{-1,0,1} : new int[]{0};
                for (int offset : offsets) {
                    LocalDate xci = fci.plusDays(offset);
                    LocalDate xco = fco.plusDays(offset);
                    if (typeMode == 0 || typeMode == 1) {
                        all.addAll(fetchHotels(q, xci, xco, a, key, false));
                    }
                    if (typeMode == 0 || typeMode == 2) {
                        all.addAll(fetchHotels(q, xci, xco, a, key, true));
                    }
                }
                Map<String,Stay> best = new LinkedHashMap<>();
                for (Stay s : all) {
                    String n = normalize(s.name);
                    Stay old = best.get(n);
                    if (old == null || s.total < old.total) best.put(n, s);
                }
                List<Stay> rows = new ArrayList<>(best.values());
                rows.sort(Comparator.comparingDouble(o -> o.total));
                runOnUiThread(() -> render(rows, fci));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("검색 오류: " + e.getMessage());
                });
            }
        });
    }

    private List<Stay> fetchHotels(String q, LocalDate ci, LocalDate co, int adults, String key, boolean vacationRental) throws Exception {
        String url = "https://serpapi.com/search.json?engine=google_hotels"
                + "&q=" + enc(q)
                + "&check_in_date=" + ci
                + "&check_out_date=" + co
                + "&adults=" + adults
                + "&currency=KRW&gl=kr&hl=ko&sort_by=3"
                + (vacationRental ? "&vacation_rentals=true" : "")
                + "&api_key=" + enc(key);

        JSONObject json = getJson(url);
        if (json.has("error")) throw new Exception(json.optString("error"));
        JSONArray properties = json.optJSONArray("properties");
        List<Stay> out = new ArrayList<>();
        if (properties == null) return out;

        long nights = ChronoUnit.DAYS.between(ci, co);
        for (int i=0; i<properties.length(); i++) {
            JSONObject p = properties.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name", "").trim();
            if (name.isEmpty()) continue;

            double total = nested(p, "total_rate", "extracted_lowest");
            double nightly = nested(p, "rate_per_night", "extracted_lowest");
            if (total <= 0 && nightly > 0) total = nightly * nights;
            if (nightly <= 0 && total > 0) nightly = total / nights;
            if (total <= 0) continue;

            String link = p.optString("link", "");
            if (!(link.startsWith("http://") || link.startsWith("https://"))) {
                link = "https://www.google.com/search?q=" + enc(name + " " + ci + " 예약");
            }

            double rating = p.optDouble("overall_rating", 0);
            int reviews = p.optInt("reviews", 0);
            String typeLabel = vacationRental ? "펜션·풀빌라·독채" : "호텔";
            out.add(new Stay(name, total, nightly, rating, reviews, ci.toString(), link, typeLabel));
        }
        return out;
    }

    private JSONObject getJson(String urlText) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(urlText).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(45000);
        c.setRequestProperty("Accept","application/json");
        InputStream in = c.getResponseCode() >= 200 && c.getResponseCode() < 300
                ? c.getInputStream() : c.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return new JSONObject(sb.toString());
    }

    private void render(List<Stay> rows, LocalDate requested) {
        progress.setVisibility(View.GONE);
        results.removeAllViews();
        status.setText("예약 가능 가격이 확인된 숙소 " + rows.size() + "곳 · 총액 낮은 순");

        if (rows.isEmpty()) {
            results.addView(text("조건에 맞는 숙소를 찾지 못했습니다.", 15, false));
            return;
        }

        int rank = 1;
        for (Stay s : rows) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMargins(0, dp(10), 0, 0);
            card.setLayoutParams(cp);
            card.setBackgroundColor(Color.rgb(245,247,250));

            TextView name = text("#" + rank + "  " + s.name, 18, true);
            card.addView(name);

            String dateText = s.date.equals(requested.toString()) ? "" : "  ·  더 저렴한 날짜 " + s.date;
            TextView meta = text(
                    "🏡 " + s.type
                            + (s.rating > 0 ? " · ⭐ " + s.rating + (s.reviews > 0 ? " · 리뷰 " + s.reviews + "개" : "") : "")
                            + dateText, 13, false);
            meta.setTextColor(Color.DKGRAY);
            card.addView(meta);

            TextView price = text("총 " + won(s.total) + "  ·  1박 약 " + won(s.nightly), 19, true);
            card.addView(price);

            Button book = button("예약 / 가격 확인");
            book.setOnClickListener(v -> open(s.link));
            card.addView(book);

            Button promo = button("인스타·네이버·여기어때·NOL 특가 검색");
            promo.setOnClickListener(v -> open("https://www.google.com/search?q=" +
                    enc(s.name + " 공동구매 공구 특가 프로모션 할인 쿠폰 이벤트 인스타 네이버 여기어때 야놀자 NOL")));
            card.addView(promo);

            Button fresh = button("신상 / 신규오픈 정보 검색");
            fresh.setOnClickListener(v -> open("https://www.google.com/search?q=" +
                    enc(s.name + " 신상숙소 신규오픈 신축 펜션 풀빌라 호텔 인스타 네이버")));
            card.addView(fresh);

            results.addView(card);
            rank++;
        }
    }

    private void pickDate(EditText target) {
        LocalDate d;
        try { d = LocalDate.parse(target.getText().toString()); }
        catch (Exception e) { d = LocalDate.now(); }
        new DatePickerDialog(this, (view,y,m,day) ->
                target.setText(String.format(Locale.KOREA,"%04d-%02d-%02d",y,m+1,day)),
                d.getYear(), d.getMonthValue()-1, d.getDayOfMonth()).show();
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        return e;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0, dp(14),0,dp(2));
        t.setLayoutParams(p);
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(17,24,39));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(0, dp(8),0,0);
        b.setLayoutParams(p);
        return b;
    }

    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("페이지를 열 수 없습니다."); }
    }

    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private String won(double n) { return String.format(Locale.KOREA,"%,.0f원",n); }
    private static String enc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); }
        catch (Exception e) { return ""; }
    }
    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z가-힣]","");
    }
    private static double nested(JSONObject o, String p, String c) {
        JSONObject x = o.optJSONObject(p);
        if (x == null) return 0;
        Object v = x.opt(c);
        if (v instanceof Number) return ((Number)v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v).replace(",","")); }
        catch (Exception e) { return 0; }
    }

    private static class Stay {
        String name, date, link, type;
        double total, nightly, rating;
        int reviews;
        Stay(String n,double t,double p,double r,int rv,String d,String l,String ty) {
            name=n; total=t; nightly=p; rating=r; reviews=rv; date=d; link=l; type=ty;
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
