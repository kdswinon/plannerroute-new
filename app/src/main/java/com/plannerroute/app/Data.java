package com.plannerroute.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

final class Data {
    static final class Place {
        String id = UUID.randomUUID().toString(), name, address = "", phone = "", memo = "";
        double lat, lon;
        int minutes = 20;
        boolean today;
        Place(String name, double lat, double lon) { this.name=name; this.lat=lat; this.lon=lon; }
        JSONObject json() throws JSONException {
            return new JSONObject().put("id",id).put("name",name).put("address",address).put("phone",phone)
                .put("memo",memo).put("lat",lat).put("lon",lon).put("minutes",minutes).put("today",today);
        }
        static Place read(JSONObject j) throws JSONException {
            double lat=j.getDouble("lat"), lon=j.getDouble("lon");
            if (!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180) throw new JSONException("좌표 범위를 확인해 주세요.");
            String name=j.getString("name").trim();
            if(name.isEmpty()||name.length()>200) throw new JSONException("업체명을 확인해 주세요.");
            Place p=new Place(name,lat,lon); p.id=j.optString("id",p.id); p.address=j.optString("address");
            p.phone=j.optString("phone"); p.memo=j.optString("memo"); p.minutes=j.optInt("minutes",20);
            if(p.minutes<0||p.minutes>1440) throw new JSONException("상담시간은 0~1440분입니다.");
            p.today=j.optBoolean("today"); return p;
        }
    }
    final SharedPreferences prefs;
    final List<Place> places = new ArrayList<>();
    Place start, destination;
    int endMode=0; // 0 return, 1 last company, 2 custom
    Data(Context c) { prefs=c.getSharedPreferences("plannerroute",Context.MODE_PRIVATE); }
    void load() throws JSONException { String s=prefs.getString("data",null); if(s!=null) restore(new JSONObject(s)); }
    JSONObject json() throws JSONException {
        JSONArray a=new JSONArray(); for(Place p:places)a.put(p.json());
        return new JSONObject().put("version",1).put("companies",a).put("start",start==null?JSONObject.NULL:start.json())
            .put("destination",destination==null?JSONObject.NULL:destination.json()).put("endMode",endMode);
    }
    void restore(JSONObject j) throws JSONException {
        if(j.getInt("version")!=1) throw new JSONException("지원하지 않는 백업 버전입니다.");
        JSONArray a=j.getJSONArray("companies"); if(a.length()>2000)throw new JSONException("최대 2,000개 업체를 가져올 수 있습니다.");
        List<Place> incoming=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<a.length();i++){Place p=Place.read(a.getJSONObject(i)); if(!ids.add(p.id)) throw new JSONException("중복된 업체 ID입니다."); incoming.add(p);}
        Place s=j.isNull("start")?null:Place.read(j.getJSONObject("start"));
        Place d=j.isNull("destination")?null:Place.read(j.getJSONObject("destination"));
        int mode=j.optInt("endMode",0); if(mode<0||mode>2)throw new JSONException("도착 방식이 올바르지 않습니다.");
        places.clear(); places.addAll(incoming); start=s; destination=d; endMode=mode;
    }
    boolean save() { try{return prefs.edit().putString("data",json().toString()).commit();}catch(JSONException e){return false;} }
    String endpoint(String kind) {
        return prefs.getString(kind,kind.equals("routing")?"https://router.project-osrm.org":kind.equals("search")?"https://photon.komoot.io":"https://tiles.openfreemap.org/styles/dark");
    }
    List<Place> selected() { List<Place> a=new ArrayList<>(); for(Place p:places)if(p.today)a.add(p); return a; }
}
