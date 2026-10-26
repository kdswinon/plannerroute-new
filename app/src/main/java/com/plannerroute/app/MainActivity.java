package com.plannerroute.app;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.*;
import org.maplibre.android.camera.*;
import org.maplibre.android.geometry.*;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.expressions.Expression;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.maplibre.android.style.layers.PropertyFactory.*;

public class MainActivity extends Activity {
    final int BG=Color.rgb(11,12,15),CARD=Color.rgb(20,22,26),SECOND=Color.rgb(27,30,35),ORANGE=Color.rgb(255,149,0),WHITE=Color.rgb(245,246,248),MUTED=Color.rgb(153,160,173);
    Data data; Routing routing; Routing.Result result; AndroidAddressSearch addressSearch;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final Handler handler=new Handler(Looper.getMainLooper());
    LinearLayout root,body,nav,mapWarnings; MapView mapView; MapLibreMap map; Style mapStyle;
    final Set<String> pinImages=new HashSet<>();
    ScrollView contentScroll; int renderedPage=-1;
    int page=0,revision=0,pickMode=0; boolean busy=false; String status="",mapError="";
    LocationManager locations; LocationListener gpsListener; Runnable gpsTimeout; Action<Data.Place> pendingLocation;
    interface Action<T>{void accept(T value);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);MapLibre.getInstance(this);data=new Data(this);routing=new Routing(this,data);addressSearch=new AndroidAddressSearch(this);
        try{data.load();}catch(Exception e){status="저장된 데이터 형식을 읽지 못했습니다. 백업 파일을 가져와 주세요.";}
        mapView=new MapView(this);mapView.onCreate(saved);
        mapView.setOnTouchListener((v,event)->{if(v.getParent()!=null)v.getParent().requestDisallowInterceptTouchEvent(event.getActionMasked()!=MotionEvent.ACTION_UP&&event.getActionMasked()!=MotionEvent.ACTION_CANCEL);return false;});
        mapView.addOnDidFailLoadingMapListener(error->{mapError="지도를 불러오지 못했습니다. 연결을 확인하고 다시 시도해 주세요.";if(!isFinishing())render();});
        mapView.getMapAsync(m->{map=m;map.setCameraPosition(new CameraPosition.Builder().target(new LatLng(36.35,127.38)).zoom(6).build());
            map.addOnMapClickListener(point->{if(pickMode==0)return false;int mode=pickMode;pickMode=0;Data.Place p=new Data.Place(mode==1?"선택한 출발지":"선택한 도착지",point.getLatitude(),point.getLongitude());
                p.address=String.format(Locale.KOREA,"지도 선택 · %.5f, %.5f",p.lat,p.lon);if(mode==1)data.start=p;else{data.destination=p;data.endMode=2;}changed();page=0;render();return true;});
            map.addOnMapLongClickListener(point->{if(pickMode!=0)return false;new AlertDialog.Builder(this).setTitle("이 위치 사용").setItems(new String[]{"출발지로 설정","도착지로 설정","업체 등록"},(d,index)->{
                Data.Place p=new Data.Place(index==0?"출발지":index==1?"도착지":"새 업체",point.getLatitude(),point.getLongitude());p.address=String.format(Locale.KOREA,"%.5f, %.5f",p.lat,p.lon);
                if(index==2)editCompany(null,p);else{if(index==0)data.start=p;else{data.destination=p;data.endMode=2;}changed();render();}}).show();return true;});loadStyle();});render();
    }
    void loadStyle(){if(map==null)return;mapStyle=null;mapError="";try{map.setStyle(data.endpoint("style"),style->{mapStyle=style;pinImages.clear();localizeMap(style);showMapError("");mapView.post(()->updateMap(true));});}catch(Exception e){mapError="지도 스타일 주소를 확인해 주세요.";render();}}
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
    TextView heading(String s,int size){TextView t=text(s,size,WHITE);t.setTypeface(null,Typeface.BOLD);return t;}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    LinearLayout card(LinearLayout into){LinearLayout c=column();c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackground(bg(CARD,22));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);into.addView(c,lp);return c;}
    Button button(String s,boolean primary,Runnable r){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?BG:WHITE);b.setBackground(bg(primary?ORANGE:SECOND,14));b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setOnClickListener(v->r.run());return b;}
    void full(LinearLayout parent,View v){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);parent.addView(v,lp);}
    void weighted(LinearLayout parent,View v){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),dp(4),dp(3),dp(4));parent.addView(v,lp);}
    EditText input(LinearLayout parent,String hint,String value,int type){EditText e=new EditText(this);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setTextSize(16);e.setSingleLine(true);e.setInputType(type);e.setHint(hint);e.setText(value);e.setPadding(dp(12),dp(12),dp(12),dp(12));e.setBackground(bg(SECOND,12));full(parent,e);return e;}
    void render(){if(isFinishing()||isDestroyed())return;
        int scrollY=contentScroll!=null&&renderedPage==page?contentScroll.getScrollY():0;renderedPage=page;
        if(mapView!=null&&mapView.getParent()!=null)((android.view.ViewGroup)mapView.getParent()).removeView(mapView);
        root=column();root.setBackgroundColor(BG);root.setFitsSystemWindows(true);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        LinearLayout header=row();header.setPadding(dp(20),dp(10),dp(14),dp(8));LinearLayout title=column();title.addView(text("PLANNERROUTE",11,ORANGE));title.addView(heading(new String[]{"오늘의 방문 동선","업체 관리","동선 지도","오늘의 통계"}[page],25));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));header.addView(button("설정",false,this::settings));root.addView(header);
        ScrollView scroll=new ScrollView(this);contentScroll=scroll;scroll.setFillViewport(true);body=column();body.setPadding(dp(16),dp(8),dp(16),dp(16));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));scroll.post(()->scroll.scrollTo(0,scrollY));
        if(!status.isEmpty()){LinearLayout c=card(body);c.addView(text(status,14,ORANGE));if(data.recoveryRequired)full(c,button("백업 파일에서 복구",false,this::backup));}
        if(page==0)today();else if(page==1)companies();else if(page==2)mapPage();else statistics();
        nav=row();nav.setPadding(dp(6),dp(6),dp(6),dp(6));nav.setBackgroundColor(CARD);String[] labels={"◉\n오늘 동선","▤\n업체 관리","⌖\n지도","▥\n통계"};
        for(int i=0;i<4;i++){final int tab=i;Button b=button(labels[i],false,()->{page=tab;pickMode=0;render();});b.setTextColor(page==i?ORANGE:MUTED);b.setBackground(bg(page==i?SECOND:CARD,14));b.setContentDescription(new String[]{"오늘 동선","업체 관리","지도","통계"}[i]);weighted(nav,b);}root.addView(nav);updateMap(false);
    }
    void today(){
        LinearLayout intro=card(body);intro.addView(heading("운전 전에, 오늘 동선 한눈에",18));intro.addView(text("방문 업체를 고르면 도로 기준 순서를 찾아드려요.",13,MUTED));
        LinearLayout start=card(body);start.addView(text("01  출발지",12,ORANGE));start.addView(heading(data.start==null?"어디서 출발하시나요?":data.start.name,18));
        if(data.start!=null)start.addView(text(data.start.address,13,MUTED));LinearLayout sr=row();weighted(sr,button("장소 검색",false,()->search(p->{data.start=p;changed();render();})));weighted(sr,button("현재 위치",false,this::gps));weighted(sr,button("지도 선택",false,()->pick(1)));start.addView(sr);
        LinearLayout selected=card(body);LinearLayout top=row();top.addView(heading("02  오늘 방문 업체",18),new LinearLayout.LayoutParams(0,-2,1));top.addView(button("전체 선택",false,()->{boolean all=!data.places.isEmpty()&&data.selected().size()==data.places.size();for(Data.Place p:data.places)p.today=!all;changed();render();}));selected.addView(top);
        selected.addView(text(data.selected().size()+"개 선택 · 최대 40개 최적화",13,MUTED));
        if(data.places.isEmpty()){selected.addView(text("등록된 업체가 없습니다. 첫 방문 업체를 추가해 보세요.",15,MUTED));full(selected,button("＋ 업체 등록",false,()->editCompany(null,null)));}
        else for(Data.Place p:data.places){CheckBox box=new CheckBox(this);box.setText(p.name+"  ·  상담 "+p.minutes+"분");box.setTextColor(WHITE);box.setTextSize(16);box.setMinHeight(dp(48));box.setButtonTintList(ColorStateList.valueOf(ORANGE));box.setChecked(p.today);box.setOnCheckedChangeListener((v,on)->{if(p.today!=on){p.today=on;changed();render();}});selected.addView(box);}
        LinearLayout end=card(body);end.addView(text("03  도착 방식",12,ORANGE));RadioGroup modes=new RadioGroup(this);String[] labels={"출발지로 돌아오기","마지막 업체에서 종료","별도 목적지"};
        for(int i=0;i<3;i++){RadioButton r=new RadioButton(this);r.setId(100+i);r.setText(labels[i]);r.setTextColor(WHITE);r.setMinHeight(dp(48));r.setButtonTintList(ColorStateList.valueOf(ORANGE));modes.addView(r);}modes.check(100+data.endMode);
        modes.setOnCheckedChangeListener((g,id)->{data.endMode=id-100;changed();render();});end.addView(modes);
        if(data.endMode==2){end.addView(text(data.destination==null?"도착지를 선택해 주세요.":data.destination.name+"\n"+data.destination.address,15,MUTED));LinearLayout er=row();weighted(er,button("장소 검색",false,()->search(p->{data.destination=p;changed();render();})));weighted(er,button("지도 선택",false,()->pick(2)));end.addView(er);}
        Button optimize=button(busy?"도로 이동시간을 계산하고 있어요…":"오늘 동선 최적화 →",true,this::optimize);optimize.setEnabled(!busy);full(body,optimize);
        body.addView(text("실시간 교통은 반영되지 않습니다. 주행 중 조작하지 마세요.",12,MUTED));
        if(result!=null){summary();mapBlock(380);timeline();}else {LinearLayout info=card(body);info.addView(text("출발지 → 방문 업체 → 도착지\n최적화 후 지도와 방문 순서가 여기에 표시됩니다.",15,MUTED));}
    }
    void summary(){LinearLayout c=card(body);c.addView(text("TODAY’S ROUTE",11,ORANGE));c.addView(heading(String.format(Locale.KOREA,"%d곳  ·  %.1f km",result.companies.size(),result.meters/1000),27));
        c.addView(text("운전 "+duration(result.seconds)+"  +  상담 "+duration(result.consultation*60),15,MUTED));c.addView(heading("총 예상 "+duration(result.seconds+result.consultation*60),20));
        c.addView(text(result.exact?"도로 시간 행렬 기준 최적 순서 · 실시간 교통 미반영":"많은 방문지를 위한 근사 최적화 · 실시간 교통 미반영",12,MUTED));
        full(c,button("카카오맵 · 경유지로 보내기",true,()->chooseRouteStops("카카오맵")));
        full(c,button("TMAP · 방문 순서에서 한 곳씩",false,()->chooseRouteStops("TMAP")));
        c.addView(text("카카오맵은 최대 6곳씩, TMAP은 선택한 방문지를 한 곳씩 보냅니다.",12,MUTED));}
    void chooseRouteStops(String kind){
        final Routing.Result plan=result;
        final int token=revision;
        if(plan==null||plan.stops.size()<2){toast("동선을 먼저 최적화해 주세요.");return;}
        final List<Data.Place> stops=new ArrayList<>(plan.stops.subList(1,plan.stops.size()));
        final String[] labels=new String[stops.size()];
        final boolean[] checked=new boolean[stops.size()];
        for(int i=0;i<stops.size();i++){
            labels[i]=(i<plan.companies.size()?(i+1)+"  ":"도착 · ")+stops.get(i).name;
            checked[i]=true;
        }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(kind+"으로 보낼 방문지")
            .setMultiChoiceItems(labels,checked,(d,i,on)->checked[i]=on)
            .setNegativeButton("취소",null).setPositiveButton("선택한 순서 확인",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(result!=plan||revision!=token){dialog.dismiss();toast("동선이 바뀌었습니다. 다시 선택해 주세요.");return;}
            List<Data.Place> chosen=new ArrayList<>();List<String> names=new ArrayList<>();
            for(int i=0;i<stops.size();i++)if(checked[i]){chosen.add(stops.get(i));names.add(labels[i]);}
            if(chosen.isEmpty()){toast("방문지를 한 곳 이상 선택해 주세요.");return;}
            dialog.dismiss();showRouteBatches(kind,plan,token,chosen,names);
        }));
        dialog.show();
    }
    void showRouteBatches(String kind,Routing.Result plan,int token,List<Data.Place> stops,List<String> names){
        if(result!=plan||revision!=token){toast("동선이 바뀌었습니다. 다시 선택해 주세요.");return;}
        int limit=kind.equals("TMAP")?1:6;
        int count=(stops.size()+limit-1)/limit;
        String[] labels=new String[count];
        for(int i=0;i<count;i++){
            int from=i*limit,to=Math.min(from+limit,stops.size());
            labels[i]=kind.equals("TMAP")?names.get(from):(i+1)+"구간 · "+names.get(from)+" → "+names.get(to-1)+" ("+(to-from)+"곳)";
        }
        new AlertDialog.Builder(this).setTitle(kind.equals("TMAP")?"TMAP · 한 곳씩 길찾기":"보낼 구간 선택 · 최대 6곳씩")
            .setItems(labels,(d,i)->confirmRouteBatch(kind,plan,token,stops,names,i*limit))
            .setNegativeButton("닫기",null).show();
    }
    void confirmRouteBatch(String kind,Routing.Result plan,int token,List<Data.Place> stops,List<String> names,int from){
        int limit=kind.equals("TMAP")?1:6;
        int to=Math.min(from+limit,stops.size());
        final List<Data.Place> batch=new ArrayList<>(stops.subList(from,to));
        StringBuilder details=new StringBuilder("출발 · 현재 위치\n");
        for(int i=from;i<to;i++)details.append(i==to-1?"목적지 · ":"경유 · ").append(names.get(i)).append("\n");
        details.append(kind.equals("TMAP")?"\n이 방문지 한 곳을 티맵 목적지로 전달합니다. 티맵 다중 경유지 자동 입력은 현재 구성에서 제공하지 않습니다. 좌표와 업체명이 티맵에 전달됩니다.":"\n최적화 순서대로 경유지 최대 5곳과 목적지 1곳을 전달합니다. 좌표가 카카오맵에 전달됩니다.");
        if(to<stops.size())details.append(kind.equals("TMAP")?"\n방문 후 이 목록에서 다음 방문지를 선택해 주세요.":"\n이 구간 방문 후 루트플래너로 돌아와 다음 구간을 선택해 주세요.");
        details.append("\n전송만으로 방문 완료 처리하지 않습니다. "+kind+"에서 방문 순서를 확인해 주세요.");
        new AlertDialog.Builder(this).setTitle(kind.equals("TMAP")?"TMAP 목적지 보내기":(from/6+1)+"구간 보내기").setMessage(details.toString())
            .setNegativeButton("뒤로",(d,w)->showRouteBatches(kind,plan,token,stops,names))
            .setPositiveButton("현재 위치에서 길찾기",(d,w)->{
                if(result!=plan||revision!=token){toast("동선이 바뀌었습니다. 다시 선택해 주세요.");return;}
                if(kind.equals("TMAP")){navigate("TMAP",batch.get(0));return;}
                requestCurrentLocation(start->{
                    if(result!=plan||revision!=token){toast("동선이 바뀌었습니다. 다시 선택해 주세요.");return;}
                    openKakao(start,batch);
                });
            }).show();
    }
    String duration(double seconds){int m=(int)Math.ceil(seconds/60);return m>=60?(m/60)+"시간 "+(m%60)+"분":m+"분";}
    void timeline(){body.addView(heading("방문 타임라인",20));LinearLayout s=card(body);s.addView(text("출발",12,ORANGE));s.addView(heading(result.stops.get(0).name,17));s.addView(text(result.stops.get(0).address,13,MUTED));
        for(int i=0;i<result.companies.size();i++){Data.Place p=result.companies.get(i);LinearLayout c=card(body);c.addView(text("↓  "+leg(i),13,ORANGE));c.addView(heading((i+1)+"   "+p.name,19));c.addView(text(p.address,14,MUTED));c.addView(text("상담 "+p.minutes+"분"+(p.memo.isEmpty()?"":" · "+p.memo),14,MUTED));navigation(c,p);}
        if(data.endMode!=1&&result.stops.size()>1){Data.Place e=result.stops.get(result.stops.size()-1);LinearLayout c=card(body);c.addView(text("↓  "+leg(result.legs.length()-1),13,ORANGE));c.addView(heading("도착 · "+(data.endMode==0?"출발지로 복귀":e.name),18));if(data.endMode!=0)navigation(c,e);}
    }
    String leg(int i){JSONObject l=result.legs.optJSONObject(i);return l==null?"":String.format(Locale.KOREA,"이동 %s / %.1f km",duration(l.optDouble("duration")),l.optDouble("distance")/1000);}
    void navigation(LinearLayout into,Data.Place p){LinearLayout r=row();for(String kind:new String[]{"TMAP","카카오맵","네이버지도"})weighted(r,button(kind,false,()->navigate(kind,p)));into.addView(r);}
    void companies(){LinearLayout actions=row();weighted(actions,button("＋ 업체 등록",true,()->editCompany(null,null)));weighted(actions,button("백업",false,this::backup));body.addView(actions);body.addView(text("총 "+data.places.size()+"개 · 휴대폰에 저장됩니다",13,MUTED));
        if(data.places.isEmpty()){LinearLayout c=card(body);c.addView(heading("첫 업체를 등록해 주세요",20));c.addView(text("주소를 검색하거나 지도에서 위치를 선택할 수 있어요.",15,MUTED));}
        for(Data.Place p:data.places){LinearLayout c=card(body);c.addView(heading(p.name,20));c.addView(text(p.address,14,MUTED));c.addView(text("상담 "+p.minutes+"분"+(p.phone.isEmpty()?"":" · "+p.phone),14,MUTED));if(!p.memo.isEmpty())c.addView(text(p.memo,14,MUTED));
            LinearLayout r=row();weighted(r,button("수정",false,()->editCompany(p,null)));weighted(r,button("삭제",false,()->new AlertDialog.Builder(this).setTitle("업체 삭제").setMessage(p.name+" 업체를 삭제할까요?").setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->{data.places.remove(p);changed();render();}).show()));c.addView(r);}
    }
    void statistics(){if(result!=null){summary();timeline();}else{LinearLayout c=card(body);c.addView(heading("오늘 동선을 먼저 계산해 주세요",20));c.addView(text("운전시간과 상담시간을 합친 하루 예상 소요시간을 보여드립니다.",15,MUTED));full(c,button("오늘 동선으로",true,()->{page=0;render();}));}LinearLayout c=card(body);c.addView(text("등록 업체 "+data.places.size()+"개\n오늘 선택 "+data.selected().size()+"개\n오늘 상담 합계 "+duration(data.selected().stream().mapToInt(p->p.minutes).sum()*60),16,WHITE));}
    void mapPage(){if(pickMode!=0){LinearLayout c=card(body);c.addView(text((pickMode==1?"출발지":"도착지")+"로 사용할 위치를 지도에서 눌러 주세요.",16,ORANGE));full(c,button("선택 취소",false,()->{pickMode=0;render();}));}mapBlock(480);body.addView(text("지도를 길게 누르면 출발지·도착지 설정 또는 업체 등록이 가능합니다.",14,MUTED));if(result!=null)summary();}
    void mapBlock(int height){LinearLayout c=card(body);LinearLayout r=row();r.addView(heading("방문 동선 지도",17),new LinearLayout.LayoutParams(0,-2,1));r.addView(button("지도",false,this::chooseMap));r.addView(button("전체 보기",false,()->updateMap(true)));c.addView(r);
        mapWarnings=column();c.addView(mapWarnings);showMapError(mapError);
        c.addView(mapView,new LinearLayout.LayoutParams(-1,dp(height)));routeOrder(c);c.addView(text("● 출발   1–40 방문 순서   ● 도착\n© OpenFreeMap · OpenMapTiles · OpenStreetMap · 검색 Android · Photon",11,MUTED));}
    void showMapError(String message){mapError=message;if(mapWarnings==null)return;mapWarnings.removeAllViews();
        if(!message.isEmpty()){mapWarnings.addView(text(message,14,ORANGE));full(mapWarnings,button("지도 다시 불러오기",false,this::loadStyle));}}
    void chooseMap(){new AlertDialog.Builder(this).setTitle("지도 표시 방식")
        .setItems(new String[]{"단순 지도 · 방문 동선 중심","상세 지도 · 도로와 주변 정보"},(d,i)->{
            data.prefs.edit().putString("style","https://tiles.openfreemap.org/styles/"+(i==0?"positron":"liberty")).apply();loadStyle();
        }).setNegativeButton("취소",null).show();}
    void localizeMap(Style style){
        String url=data.endpoint("style");if(!url.equals("https://tiles.openfreemap.org/styles/liberty")&&!url.equals("https://tiles.openfreemap.org/styles/positron"))return;
        for(Layer layer:style.getLayers())if(layer instanceof SymbolLayer){SymbolLayer labels=(SymbolLayer)layer;
            PropertyValue<?> field=labels.getTextField();
            if(field.isExpression()&&field.getExpression().toString().contains("\"name\""))
                labels.setProperties(textField(Expression.coalesce(Expression.get("name:ko"),Expression.get("name"),Expression.get("name:nonlatin"),Expression.get("name:latin"))));
        }
    }
    void routeOrder(LinearLayout parent){
        if(result==null){parent.addView(text("최적화하면 방문 순서 번호가 표시됩니다.",13,MUTED));return;}
        parent.addView(text("방문 순서 · 도로 이동시간 기준",14,ORANGE));
        parent.addView(text("출발 · "+result.stops.get(0).name,14,WHITE));
        for(int i=0;i<result.companies.size();i++){
            final Data.Place p=result.companies.get(i);
            Button stop=button((i+1)+"  "+p.name+"  ·  "+leg(i),false,()->{
                if(map!=null)map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(p.lat,p.lon),15));
                Rect bounds=new Rect();mapView.getDrawingRect(bounds);body.offsetDescendantRectToMyCoords(mapView,bounds);contentScroll.smoothScrollTo(0,Math.max(0,bounds.top));
            });full(parent,stop);parent.addView(text(p.address,12,MUTED));navigation(parent,p);
        }
        parent.addView(text(data.endMode==0?"도착 · 출발지로 복귀":data.endMode==1?"도착 · 마지막 업체에서 종료":"도착 · "+result.stops.get(result.stops.size()-1).name,14,WHITE));
        if(data.endMode!=1)navigation(parent,result.stops.get(result.stops.size()-1));
    }
    String pinImage(String label,String kind){
        String id="planner-pin-"+kind+"-"+label;
        if(!pinImages.contains(id)){
            Bitmap image=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(image);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);canvas.drawCircle(40,40,39,paint);
            paint.setColor(kind.equals("start")?Color.rgb(24,137,65):kind.equals("end")?Color.rgb(0,102,180):ORANGE);canvas.drawCircle(40,40,35,paint);
            paint.setColor(kind.equals("stop")?BG:Color.WHITE);paint.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(label.length()>2?24:30);
            canvas.drawText(label,40,40-(paint.ascent()+paint.descent())/2,paint);
            mapStyle.addImage(id,image);pinImages.add(id);
        }return id;
    }
    void pick(int mode){pickMode=mode;page=2;render();}
    void changed(){revision++;result=null;status="";if(!data.save())status=data.recoveryRequired?"원본 저장자료를 보호하기 위해 저장을 중단했습니다. 정상 백업 파일을 가져와 주세요.":"저장 공간을 확인해 주세요. 이번 변경을 저장하지 못했습니다.";updateMap(false);}
    void optimize(){if(busy)return;if(data.start==null){toast("출발지를 먼저 선택해 주세요.");return;}if(data.endMode==2&&data.destination==null){toast("도착지를 먼저 선택해 주세요.");return;}
        List<Data.Place> selected=data.selected();if(selected.isEmpty()||selected.size()>40){toast("업체를 1~40개 선택해 주세요.");return;}
        final int token=revision,mode=data.endMode;Data.Place start=data.start,end=data.destination;busy=true;status="도로 이동시간을 비교하고 있습니다.";render();
        worker.execute(()->{try{Routing.Result r=routing.calculate(start,selected,end,mode);handler.post(()->{busy=false;if(token==revision){result=r;status="";render();mapView.post(()->updateMap(true));}else{status="조건이 바뀌었습니다. 동선을 다시 계산해 주세요.";render();}});}catch(Exception e){handler.post(()->{busy=false;if(token==revision){result=null;status="동선 계산 실패 · "+message(e);}render();});}});
    }
    void search(Action<Data.Place> done){LinearLayout v=column();v.setPadding(dp(20),dp(4),dp(20),dp(12));EditText q=input(v,"주소 또는 장소명","",1);v.addView(text("휴대폰 주소 검색을 우선 사용합니다. 결과의 도로명과 건물번호가 맞는지 확인해 주세요.",13,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("주소·장소 검색").setView(v).setNegativeButton("취소",null).setPositiveButton("검색",null).create();dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(x2->{String query=q.getText().toString().trim();if(query.length()<2){q.setError("두 글자 이상 입력해 주세요.");return;}Button b=dialog.getButton(-1);b.setEnabled(false);b.setText("검색 중…");
            worker.execute(()->{try{List<Data.Place> found=routing.search(query,addressSearch::search);handler.post(()->{if(isFinishing()||isDestroyed()||!dialog.isShowing())return;b.setEnabled(true);b.setText("검색");if(found.isEmpty()){toast("검색 결과가 없습니다. 지도에서 위치를 선택할 수 있습니다.");return;}dialog.dismiss();showAddressResults(found,done);});}catch(Exception e){handler.post(()->{if(isFinishing()||isDestroyed()||!dialog.isShowing())return;b.setEnabled(true);b.setText("검색");toast("검색 실패 · "+message(e));});}});
        }));dialog.show();}
    void showAddressResults(List<Data.Place> found,Action<Data.Place> done){
        if(found.isEmpty()||isFinishing()||isDestroyed())return;
        final int[] selected={0};
        String[] labels=found.stream().map(p->p.name.matches("[0-9-]+")?p.address:p.name+"\n"+p.address).toArray(String[]::new);
        AlertDialog results=new AlertDialog.Builder(this).setTitle("사용할 주소를 선택하세요")
            .setSingleChoiceItems(labels,0,(dialog,index)->selected[0]=index)
            .setNegativeButton("취소",null).setPositiveButton("이 주소 사용",null).create();
        results.setOnShowListener(ignored->results.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            Data.Place chosen=found.get(selected[0]);
            // Close the result window explicitly before updating the underlying editor.
            results.dismiss();done.accept(chosen);
        }));
        results.show();
    }
    void editCompany(Data.Place old,Data.Place seed){if(data.recoveryRequired){toast("원본 보호를 위해 백업 파일을 먼저 복구해 주세요.");return;}if(old==null&&data.places.size()>=2000){toast("업체는 최대 2,000개 등록할 수 있습니다.");return;}LinearLayout v=column();v.setPadding(dp(20),dp(4),dp(20),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(v);Data.Place p=old!=null?old:seed;final Data.Place[] location={p};
        EditText name=input(v,"업체명",p==null?"":p.name,1);TextView address=text(p==null?"위치가 지정되지 않았습니다.":p.address,14,MUTED);v.addView(address);
        EditText lat=input(v,"위도 (지도 좌표 직접 입력 가능)",p==null?"":Double.toString(p.lat),8194|4096),lon=input(v,"경도",p==null?"":Double.toString(p.lon),8194|4096);
        full(v,button("주소·장소 검색",false,()->search(found->{location[0]=found;address.setText(found.address);lat.setText(Double.toString(found.lat));lon.setText(Double.toString(found.lon));if(name.getText().toString().trim().isEmpty()&&!found.name.matches("[0-9-]+"))name.setText(found.name);})));
        full(v,button("검색한 좌표 적용",false,()->{if(location[0]!=null){lat.setText(Double.toString(location[0].lat));lon.setText(Double.toString(location[0].lon));}}));
        EditText phone=input(v,"전화번호",p==null?"":p.phone,3),minutes=input(v,"상담 예상시간 (분)",p==null?"20":Integer.toString(p.minutes),2),memo=input(v,"메모",p==null?"":p.memo,1);
        AlertDialog d=new AlertDialog.Builder(this).setTitle(old==null?"업체 등록":"업체 수정").setView(scroll).setNegativeButton("취소",null).setPositiveButton("저장",null).create();
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(w->{try{String label=name.getText().toString().trim();if(label.isEmpty()||label.length()>200)throw new Exception("업체명을 1~200자로 입력해 주세요.");
            if(old==null&&data.places.size()>=2000)throw new Exception("업체는 최대 2,000개 등록할 수 있습니다.");
            String latStr=lat.getText().toString().trim(),lonStr=lon.getText().toString().trim();
            double la=!latStr.isEmpty()?Double.parseDouble(latStr):location[0]!=null?location[0].lat:Double.NaN;
            double lo=!lonStr.isEmpty()?Double.parseDouble(lonStr):location[0]!=null?location[0].lon:Double.NaN;
            int time=Integer.parseInt(minutes.getText().toString());if(!Double.isFinite(la)||!Double.isFinite(lo)||Math.abs(la)>85||Math.abs(lo)>180||time<0||time>1440)throw new Exception("좌표와 상담시간(0~1440분)을 확인해 주세요.");
            Data.Place value=new Data.Place(label,la,lo);if(old!=null){value.id=old.id;value.today=old.today;}
            if(location[0]!=null&&Math.abs(location[0].lat-la)<1e-6&&Math.abs(location[0].lon-lo)<1e-6)value.address=location[0].address;
            else value.address=String.format(Locale.KOREA,"%.5f, %.5f",la,lo);
            value.phone=phone.getText().toString();value.minutes=time;value.memo=memo.getText().toString();
            if(old!=null)data.places.set(data.places.indexOf(old),value);else data.places.add(value);changed();d.dismiss();render();
        }catch(Exception e){toast(e instanceof NumberFormatException?"좌표와 상담시간을 숫자로 입력해 주세요.":message(e));}}));d.show();
    }
    void gps(){requestCurrentLocation(p->{data.start=p;changed();render();});}
    void requestCurrentLocation(Action<Data.Place> done){
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            pendingLocation=done;requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},7);return;
        }
        pendingLocation=null;stopGps();locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        boolean precise=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        List<String> enabled=locations.getProviders(true),providers=new ArrayList<>();
        if(precise&&enabled.contains(LocationManager.GPS_PROVIDER))providers.add(LocationManager.GPS_PROVIDER);
        if(enabled.contains(LocationManager.NETWORK_PROVIDER))providers.add(LocationManager.NETWORK_PROVIDER);
        if(providers.isEmpty()){toast("휴대폰 위치 서비스를 켠 뒤 다시 눌러 주세요.");return;}
        toast("현재 위치를 확인하고 있습니다…");
        gpsListener=new LocationListener(){public void onLocationChanged(Location l){
            if(gpsListener!=this)return;
            long age=SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos();
            if(age<0||age>60_000_000_000L||!l.hasAccuracy()||l.getAccuracy()>500||!Double.isFinite(l.getLatitude())||!Double.isFinite(l.getLongitude())||Math.abs(l.getLatitude())>85||Math.abs(l.getLongitude())>180)return;
            stopGps();Data.Place current=new Data.Place("현재 위치",l.getLatitude(),l.getLongitude());current.address="현재 위치 · 정확도 약 "+Math.round(l.getAccuracy())+"m";
            done.accept(current);
        }public void onProviderEnabled(String s){}public void onProviderDisabled(String s){}public void onStatusChanged(String s,int n,Bundle b){}};
        boolean registered=false;
        for(String provider:providers)try{locations.requestLocationUpdates(provider,1000,0,gpsListener,Looper.getMainLooper());registered=true;}catch(SecurityException|IllegalArgumentException ignored){}
        if(!registered){stopGps();toast("위치 권한과 휴대폰 위치 설정을 확인해 주세요.");return;}
        gpsTimeout=()->{stopGps();toast("현재 위치를 확인하지 못했습니다. 정확한 위치 권한과 GPS를 확인한 뒤 다시 눌러 주세요.");};handler.postDelayed(gpsTimeout,25000);
    }
    void stopGps(){if(locations!=null&&gpsListener!=null)try{locations.removeUpdates(gpsListener);}catch(SecurityException ignored){}gpsListener=null;if(gpsTimeout!=null)handler.removeCallbacks(gpsTimeout);gpsTimeout=null;}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);if(code!=7)return;
        Action<Data.Place> done=pendingLocation;pendingLocation=null;
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){if(done!=null)requestCurrentLocation(done);else toast("위치 권한이 허용되었습니다. 원하시는 버튼을 다시 눌러 주세요.");}
        else toast("현재 위치를 출발지로 전달하려면 위치 권한이 필요합니다.");
    }
    void updateMap(boolean fit){if(mapStyle==null||map==null)return;try{
        JSONArray features=new JSONArray();List<Data.Place> points=new ArrayList<>();
        if(data.start!=null){points.add(data.start);features.put(feature(data.start,result!=null&&data.endMode==0?"출·도":"출발","start"));}
        List<Data.Place> chosen=result==null?data.selected():result.companies;
        for(int i=0;i<chosen.size();i++){Data.Place p=chosen.get(i);points.add(p);boolean last=result!=null&&data.endMode==1&&i==chosen.size()-1;features.put(feature(p,result==null?"·":Integer.toString(i+1),last?"end":"stop"));}
        if(result!=null){
            if(data.endMode==2&&data.destination!=null){points.add(data.destination);features.put(feature(data.destination,"도착","end"));}
        }else if(data.endMode==2&&data.destination!=null){points.add(data.destination);features.put(feature(data.destination,"도착","end"));}
        JSONObject collection=new JSONObject().put("type","FeatureCollection").put("features",features);
        GeoJsonSource source=mapStyle.getSourceAs("stops");if(source==null){source=new GeoJsonSource("stops",collection.toString());mapStyle.addSource(source);
            mapStyle.addLayer(new SymbolLayer("stops-circles","stops").withProperties(iconImage(Expression.get("pin")),iconSize(.65f),iconAllowOverlap(true),iconIgnorePlacement(true)));
        }else source.setGeoJson(collection.toString());
        JSONObject geometry=result==null?new JSONObject().put("type","FeatureCollection").put("features",new JSONArray()):new JSONObject().put("type","Feature").put("properties",new JSONObject()).put("geometry",result.geometry);
        GeoJsonSource roads=mapStyle.getSourceAs("roads");if(roads==null){roads=new GeoJsonSource("roads",geometry.toString());mapStyle.addSource(roads);mapStyle.addLayerBelow(new LineLayer("route-halo","roads").withProperties(lineColor("#FFFFFF"),lineWidth(7f),lineOpacity(.95f),lineJoin("round"),lineCap("round")),"stops-circles");mapStyle.addLayerBelow(new LineLayer("route-line","roads").withProperties(lineColor("#FF9500"),lineWidth(4f),lineJoin("round"),lineCap("round")),"stops-circles");}else roads.setGeoJson(geometry.toString());
        if(fit&&!points.isEmpty()){LatLngBounds.Builder bounds=new LatLngBounds.Builder();Set<String> distinct=new HashSet<>();for(Data.Place p:points){bounds.include(new LatLng(p.lat,p.lon));distinct.add(p.lat+","+p.lon);}
            if(result!=null){JSONArray coords=result.geometry.getJSONArray("coordinates");for(int i=0;i<coords.length();i++){JSONArray a=coords.getJSONArray(i);bounds.include(new LatLng(a.getDouble(1),a.getDouble(0)));distinct.add(a.toString());}}
            if(distinct.size()<2)map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(points.get(0).lat,points.get(0).lon),14));else map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),dp(40)));}
    }catch(Exception e){showMapError("지도 표시를 갱신하지 못했습니다. 다시 불러와 주세요.");}}
    JSONObject feature(Data.Place p,String label,String kind)throws JSONException{return new JSONObject().put("type","Feature").put("properties",new JSONObject().put("label",label).put("kind",kind).put("pin",pinImage(label,kind))).put("geometry",new JSONObject().put("type","Point").put("coordinates",new JSONArray().put(p.lon).put(p.lat)));}
    void navigate(String kind,Data.Place p){
        if(kind.equals("카카오맵")){requestCurrentLocation(start->openKakao(start,p));return;}
        String name=Uri.encode(p.name),scheme,web;
        if(kind.equals("TMAP")){scheme="tmap://route?goalx="+p.lon+"&goaly="+p.lat+"&goalname="+name;web="https://www.tmap.co.kr/";}
        else{scheme="nmap://navigation?dlat="+p.lat+"&dlng="+p.lon+"&dname="+name+"&appname="+getPackageName();web="https://map.naver.com/p/search/"+Uri.encode(p.name+" "+p.address);}
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(scheme)));}catch(ActivityNotFoundException e){if(kind.equals("TMAP"))new AlertDialog.Builder(this).setTitle("TMAP이 설치되어 있지 않습니다").setMessage("TMAP은 웹에서 차량 안내를 제공하지 않습니다. 설치 페이지를 열거나 카카오맵 웹 길찾기를 이용해 주세요.").setNegativeButton("취소",null).setNeutralButton("웹 길찾기",(d,w)->openWeb("https://map.kakao.com/link/to/"+name+","+p.lat+","+p.lon)).setPositiveButton("TMAP 설치",(d,w)->openWeb("https://play.google.com/store/apps/details?id=com.skt.tmap.ku")).show();else openWeb(web);}
    }
    void openKakao(Data.Place start,Data.Place destination){openKakao(start,Collections.singletonList(destination));}
    void openKakao(Data.Place start,List<Data.Place> stops){
        if(stops.isEmpty()||stops.size()>6){toast("한 번에 보낼 방문지는 1~6곳이어야 합니다.");return;}
        Data.Place destination=stops.get(stops.size()-1);
        StringBuilder query=new StringBuilder("sp=").append(start.lat).append(",").append(start.lon);
        for(int i=0;i<stops.size()-1;i++){
            Data.Place p=stops.get(i);
            query.append("&vp").append(i==0?"":Integer.toString(i+1)).append("=").append(p.lat).append(",").append(p.lon);
        }
        query.append("&ep=").append(destination.lat).append(",").append(destination.lon).append("&by=car");
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("kakaomap://route?"+query)));}
        catch(ActivityNotFoundException e){openWeb("https://m.map.kakao.com/scheme/route?"+query);}
    }
    void openWeb(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){toast("링크를 열 브라우저가 없습니다.");}}
    void backup(){new AlertDialog.Builder(this).setTitle("업체 데이터 백업").setItems(new String[]{"JSON 내보내기","JSON 가져오기"},(d,i)->{try{Intent intent=new Intent(i==0?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);intent.setType("application/json");intent.addCategory(Intent.CATEGORY_OPENABLE);if(i==0)intent.putExtra(Intent.EXTRA_TITLE,"plannerroute-backup.json");startActivityForResult(intent,i==0?21:22);}catch(ActivityNotFoundException e){toast("휴대폰의 파일 앱을 활성화해 주세요.");}}).show();}
    @Override protected void onActivityResult(int request,int code,Intent intent){super.onActivityResult(request,code,intent);if(code!=RESULT_OK||intent==null||intent.getData()==null)return;Uri uri=intent.getData();
        try{if(request==21){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("파일을 열 수 없습니다.");String backup=data.recoveryRequired?data.prefs.getString("data",""):data.json().toString(2);out.write(backup.getBytes(StandardCharsets.UTF_8));}toast(data.recoveryRequired?"읽기에 실패한 원본을 복구용 파일로 보관했습니다.":"백업 파일을 저장했습니다.");}
            else if(request==22){byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("파일을 열 수 없습니다.");byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>2_000_000)throw new IOException("백업 파일은 2MB 이하여야 합니다.");out.write(b,0,n);}bytes=out.toByteArray();}
                JSONObject incoming=new JSONObject(new String(bytes,StandardCharsets.UTF_8));Data checked=new Data(this);checked.restore(incoming);
                new AlertDialog.Builder(this).setTitle("백업 가져오기").setMessage("업체 "+checked.places.size()+"개를 가져옵니다. 현재 업체와 출발지 설정을 교체합니다. 필요한 경우 먼저 내보내기 해 주세요.").setNegativeButton("취소",null).setPositiveButton("교체하여 가져오기",(d,w)->{try{data.restore(incoming);changed();render();}catch(Exception e){toast(message(e));}}).show();}
        }catch(Exception e){toast("백업 처리 실패 · "+message(e));}
    }
    void settings(){LinearLayout v=column();v.setPadding(dp(20),dp(4),dp(20),dp(12));v.addView(text("지도와 도로 계산에는 인터넷이 필요합니다. 업체·메모는 휴대폰 내부에 저장됩니다. 검색어는 검색 서버로, 경로 좌표는 도로 서버로 전달됩니다. 전화번호·메모는 전송하지 않습니다.",14,MUTED));
        v.addView(text("OSRM 시간은 실시간 교통·국내 통행 제한을 모두 반영하지 않습니다. 실제 운전은 내비게이션 안내를 따르세요.",14,ORANGE));
        EditText route=input(v,"도로 서버 HTTPS 주소",data.endpoint("routing"),17),search=input(v,"보조 검색 서버 HTTPS 주소",data.endpoint("search"),17),style=input(v,"지도 스타일 HTTPS 주소",data.endpoint("style"),17);
        full(v,button("JSON 백업 · 가져오기",false,this::backup));full(v,button("지도 데이터 출처",false,()->openWeb("https://www.openstreetmap.org/copyright")));
        ScrollView scroll=new ScrollView(this);scroll.addView(v);AlertDialog d=new AlertDialog.Builder(this).setTitle("루트플래너 0.1.9").setView(scroll).setNegativeButton("닫기",null).setPositiveButton("저장",null).create();
        d.setOnShowListener(x->d.getButton(-1).setOnClickListener(w->{String[] values={route.getText().toString().trim(),search.getText().toString().trim(),style.getText().toString().trim()};for(String s:values){Uri u=Uri.parse(s);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null){toast("인증정보나 쿼리 없는 HTTPS 주소를 입력해 주세요.");return;}}
            data.prefs.edit().putString("routing",values[0].replaceAll("/+$","")).putString("search",values[1].replaceAll("/+$","")).putString("style",values[2]).apply();revision++;result=null;d.dismiss();loadStyle();render();}));d.show();
    }
    String message(Exception e){String m=e.getMessage();return m==null?"연결 상태와 입력값을 확인해 주세요.":m;}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onStart(){super.onStart();mapView.onStart();}
    @Override protected void onResume(){super.onResume();mapView.onResume();}
    @Override protected void onPause(){mapView.onPause();stopGps();super.onPause();}
    @Override protected void onStop(){mapView.onStop();super.onStop();}
    @Override protected void onDestroy(){stopGps();worker.shutdownNow();if(addressSearch!=null)addressSearch.close();mapView.onDestroy();super.onDestroy();}
    @Override public void onLowMemory(){super.onLowMemory();mapView.onLowMemory();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);mapView.onSaveInstanceState(out);}
}
