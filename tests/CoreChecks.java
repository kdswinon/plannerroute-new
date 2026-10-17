package com.plannerroute.app;

import android.content.SharedPreferences;
import com.sun.net.httpserver.HttpServer;
import org.json.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM tests of the actual data/routing classes. Does not emulate Android UI or GPS. */
public final class CoreChecks {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    interface Throwing {void run() throws Exception;}
    static void reject(Throwing action,String label)throws Exception{boolean rejected=false;try{action.run();}catch(JSONException|IOException e){rejected=true;}check(rejected,label);}
    static class Storage {
        Map<String,String> values=new HashMap<>();boolean failure;
        SharedPreferences prefs=(SharedPreferences)Proxy.newProxyInstance(CoreChecks.class.getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getString"))return values.getOrDefault((String)a[0],(String)a[1]);
            if(m.getName().equals("edit")){
                Map<String,String> pending=new HashMap<>();
                return Proxy.newProxyInstance(CoreChecks.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(editor,method,args)->{
                    if(method.getName().equals("putString")){pending.put((String)args[0],(String)args[1]);return editor;}
                    if(method.getName().equals("commit")||method.getName().equals("apply")){if(!failure)values.putAll(pending);return method.getReturnType()==boolean.class?!failure:null;}
                    throw new UnsupportedOperationException(method.getName());
                });
            }throw new UnsupportedOperationException(m.getName());
        });
    }
    static Data.Place place(String name,double lat,double lon){return new Data.Place(name,lat,lon);}
    static void data()throws Exception{
        Storage storage=new Storage();Data data=new Data(storage.prefs);
        Data.Place p=place("확인용 업체",37.5665,126.978);p.memo="줄바꿈\n상담 메모";p.phone="02-000-0000";p.minutes=40;p.today=true;data.places.add(p);
        data.start=place("출발지",37.55,126.97);data.endMode=2;data.destination=place("도착지",37.5,127.0);
        check(data.save(),"save");Data loaded=new Data(storage.prefs);loaded.load();
        check(loaded.places.size()==1&&loaded.places.get(0).memo.equals(p.memo)&&loaded.places.get(0).phone.equals(p.phone),"fields roundtrip");
        check(loaded.selected().size()==1&&loaded.endMode==2&&loaded.destination.name.equals("도착지"),"selected/end roundtrip");
        String before=loaded.json().toString();JSONObject invalid=new JSONObject(before);invalid.getJSONArray("companies").getJSONObject(0).put("lat",100);
        reject(()->loaded.restore(invalid),"invalid latitude rejected");check(before.equals(loaded.json().toString()),"failed import preserves existing data");
        JSONObject duplicate=new JSONObject(before);duplicate.getJSONArray("companies").put(duplicate.getJSONArray("companies").getJSONObject(0));reject(()->loaded.restore(duplicate),"duplicate rejected");
        for(Object time:new Object[]{-1,1441,1.5,"bad","20",JSONObject.NULL}){JSONObject j=p.json().put("minutes",time);reject(()->Data.Place.read(j),"invalid consultation: "+time);}
        for(int time:new int[]{0,20,1440})check(Data.Place.read(p.json().put("minutes",time)).minutes==time,"consultation boundary");
        reject(()->Data.Place.read(p.json().put("name","x".repeat(201))),"long name");
        reject(()->Data.Place.read(p.json().put("id"," ")),"blank id");
        JSONObject tooMany=new JSONObject(before);JSONArray big=new JSONArray();for(int i=0;i<2001;i++)big.put(p.json().put("id","id"+i));tooMany.put("companies",big);reject(()->loaded.restore(tooMany),"2001 companies");
        storage.failure=true;String durable=storage.values.get("data");loaded.places.clear();check(!loaded.save(),"failed disk write reported");check(durable.equals(storage.values.get("data")),"failed disk write retains durable data");storage.failure=false;
        storage.values.put("data","{broken original}");Data broken=new Data(storage.prefs);reject(broken::load,"corrupt load");broken.start=p;
        check(!broken.save(),"corrupt original save blocked");check(storage.values.get("data").equals("{broken original}"),"corrupt original preserved");
        broken.restore(new JSONObject(before));check(broken.save(),"explicit valid restore unlocks save");
    }
    static double brute(List<Integer> a,int k,double[][] c,int end){if(k==a.size())return Routing.score(a,c,end);double best=Double.POSITIVE_INFINITY;for(int i=k;i<a.size();i++){Collections.swap(a,k,i);best=Math.min(best,brute(a,k+1,c,end));Collections.swap(a,k,i);}return best;}
    static void optimize(){Random random=new Random(735);for(int n=1;n<=7;n++)for(int mode=0;mode<3;mode++)for(int run=0;run<8;run++){
        double[][] c=new double[n+2][n+2];for(int i=0;i<c.length;i++)for(int j=0;j<c.length;j++)c[i][j]=i==j?0:1+random.nextInt(1000);int end=mode==0?0:mode==1?-1:n+1;
        List<Integer> all=new ArrayList<>();for(int i=1;i<=n;i++)all.add(i);double best=brute(all,0,c,end);List<Integer> exact=Routing.exact(c,n,end);
        check(Math.abs(Routing.score(exact,c,end)-best)<.001,"exact asymmetric route matches brute force");List<Integer> h=Routing.heuristic(c,n,end);
        check(h!=null&&new HashSet<>(h).size()==n&&Routing.score(h,c,end)+.001>=best,"heuristic permutation and directed score");}
        double[][] unreachable={{0,Double.POSITIVE_INFINITY},{Double.POSITIVE_INFINITY,0}};check(Routing.exact(unreachable,1,0)==null,"unreachable exact");check(Routing.heuristic(unreachable,1,0)==null,"unreachable heuristic");
        double[][] large=new double[42][42];for(int i=0;i<42;i++)for(int j=0;j<42;j++)large[i][j]=i==j?0:random.nextInt(1000)+1;
        long now=System.nanoTime();List<Integer> h=Routing.heuristic(large,40,41);check(h.size()==40&&new HashSet<>(h).size()==40,"40 companies");System.out.printf(Locale.ROOT,"40-company heuristic: %.0f ms%n",(System.nanoTime()-now)/1e6);
    }
    static JSONObject table(int n){JSONArray rows=new JSONArray();for(int i=0;i<n;i++){JSONArray row=new JSONArray();for(int j=0;j<n;j++)row.put(i==j?0:Math.abs(i-j)*100+10);rows.put(row);}return new JSONObject().put("code","Ok").put("durations",rows);}
    static JSONObject route(String coords){String[] points=coords.split(";");JSONArray xy=new JSONArray(),legs=new JSONArray();for(String point:points){String[] pair=point.split(",");xy.put(new JSONArray().put(Double.parseDouble(pair[0])).put(Double.parseDouble(pair[1])));}for(int i=1;i<points.length;i++)legs.put(new JSONObject().put("distance",1000).put("duration",120));return new JSONObject().put("code","Ok").put("routes",new JSONArray().put(new JSONObject().put("distance",1000*(points.length-1)).put("duration",120*(points.length-1)).put("legs",legs).put("geometry",new JSONObject().put("type","LineString").put("coordinates",xy))));}
    static void network(Path folder)throws Exception{
        Storage storage=new Storage();Data data=new Data(storage.prefs);AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{requests.incrementAndGet();String path=exchange.getRequestURI().getPath();JSONObject response;
            if(path.startsWith("/table/")){String coords=path.substring(path.lastIndexOf('/')+1);response=table(coords.split(";").length);}
            else if(path.startsWith("/route/"))response=route(path.substring(path.lastIndexOf('/')+1));
            else if(path.startsWith("/api/"))response=new JSONObject().put("features",new JSONArray().put(new JSONObject().put("properties",new JSONObject().put("name","확인용 검색").put("city","서울")).put("geometry",new JSONObject().put("coordinates",new JSONArray().put(126.978).put(37.5665)))));
            else {exchange.sendResponseHeaders(503,-1);return;}
            byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        try{String base="http://127.0.0.1:"+server.getAddress().getPort();storage.values.put("routing",base);storage.values.put("search",base);Routing routing=new Routing(folder.toFile(),data);
            Data.Place start=place("시작",37.55,126.97),a=place("A",37.5665,126.978),b=place("B",37.4979,127.0276),end=place("끝",37.51,127.03);a.minutes=20;b.minutes=40;
            for(int mode=0;mode<3;mode++){Routing.Result r=routing.calculate(start,Arrays.asList(a,b),end,mode);check(r.stops.size()==(mode==1?3:4),"end mode stops");check(r.consultation==60,"consultation sum");check(r.legs.length()==r.stops.size()-1,"route leg count");check(r.geometry.getString("type").equals("LineString"),"GeoJSON route");check(r.seconds==120*r.legs.length(),"driving seconds not consultation");}
            int before=requests.get();routing.calculate(start,Arrays.asList(a,b),end,2);check(requests.get()==before,"same route/matrix cached");
            check(routing.search("확인용").size()==1,"search parsing");before=requests.get();routing.search("확인용");check(requests.get()==before,"search cached");
            reject(()->routing.get(base+"/unavailable",1000),"503 surfaced");
            reject(()->routing.calculate(start,Collections.emptyList(),end,0),"empty selection");
        }finally{server.stop(0);}
    }
    static void live(Path folder)throws Exception{
        Data data=new Data(new Storage().prefs);Routing routing=new Routing(folder.toFile(),data);Data.Place start=place("서울시청",37.5665,126.978),a=place("강남역",37.4979,127.0276),b=place("서울역",37.5547,126.9707);a.minutes=20;b.minutes=40;
        Routing.Result r=routing.calculate(start,Arrays.asList(a,b),null,0);check(r.geometry.getJSONArray("coordinates").length()>4,"live road geometry");check(r.meters>0&&r.seconds>0&&r.legs.length()==3&&r.consultation==60,"live distances/times/consultation");
        System.out.printf(Locale.ROOT,"LIVE OSRM: %.1f km, %.1f driving min, %d consultation min, %d road points%n",r.meters/1000,r.seconds/60,r.consultation,r.geometry.getJSONArray("coordinates").length());
        check(!routing.search("Seoul").isEmpty(),"live Photon");
    }
    static Path sharedTestDirectory(Path work)throws IOException {
        // Synthetic HTTP fixtures only: allow the workspace's shared group to scan
        // this directory from creation time. Windows keeps inherited ACLs.
        if(Files.getFileStore(work).supportsFileAttributeView("posix")){
            var permissions=PosixFilePermissions.fromString("rwxr-x---");
            Path dir=Files.createTempDirectory(work,"http-",
                PosixFilePermissions.asFileAttribute(permissions));
            if(!Files.getPosixFilePermissions(dir).equals(permissions))Files.setPosixFilePermissions(dir,permissions);
            return dir;
        }
        return Files.createTempDirectory(work,"http-");
    }
    public static void main(String[] args)throws Exception{Path work=Path.of(args.length>0?args[0]:".tooling/test-runtime");Files.createDirectories(work);data();optimize();network(sharedTestDirectory(work));if(Arrays.asList(args).contains("--live"))live(work.resolve("live"));System.out.println("PASS: "+checks+" core checks. Android UI/GPS/navigation not exercised.");}
}
