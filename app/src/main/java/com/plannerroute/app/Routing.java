package com.plannerroute.app;

import android.content.Context;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class Routing {
    final File cache;
    final Data data;
    Routing(Context c,Data data) {this(new File(c.getCacheDir(),"roads"),data);}
    Routing(File cache,Data data) {this.data=data;this.cache=cache;cache.mkdirs();}
    JSONObject get(String url,long ttl) throws Exception {
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)))hash.append(String.format(Locale.ROOT,"%02x",b&255));String key=hash.toString();
        File f=new File(cache,key+".json");
        if(f.exists()&&System.currentTimeMillis()-f.lastModified()<ttl){try{return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){}}
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(15000);c.setReadTimeout(35000);
        c.setRequestProperty("User-Agent","plannerroute/0.1 (Android; personal route planner)");
        try {
            int status=c.getResponseCode(); if(status!=200)throw new IOException("서버 응답 "+status+". 잠시 후 다시 시도해 주세요.");
            byte[] bytes;
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>8_000_000)throw new IOException("응답이 너무 큽니다.");out.write(b,0,n);} bytes=out.toByteArray();
            }
            JSONObject j=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            try {java.nio.file.Files.write(f.toPath(),bytes); trim();}catch(IOException ignored){}
            return j;
        } finally {c.disconnect();}
    }
    void trim(){File[] fs=cache.listFiles();if(fs==null||fs.length<=100)return;Arrays.sort(fs,Comparator.comparingLong(File::lastModified));for(int i=0;i<fs.length-100;i++)fs[i].delete();}
    List<Data.Place> search(String query) throws Exception {
        JSONObject j=get(data.endpoint("search")+"/api/?limit=5&q="+URLEncoder.encode(query,StandardCharsets.UTF_8.name()),30L*86400000);
        JSONArray features=j.getJSONArray("features");List<Data.Place> out=new ArrayList<>();
        for(int i=0;i<features.length();i++){
            JSONObject f=features.getJSONObject(i),p=f.getJSONObject("properties"); JSONArray xy=f.getJSONObject("geometry").getJSONArray("coordinates");
            String label=p.optString("name",query).trim();if(label.isEmpty())label=query;if(label.length()>200)label=label.substring(0,200);
            Data.Place v=new Data.Place(label,xy.getDouble(1),xy.getDouble(0));
            StringJoiner address=new StringJoiner(" "); for(String k:new String[]{"state","city","district","street","housenumber"}){String s=p.optString(k);if(!s.isEmpty())address.add(s);} v.address=address.toString();if(v.address.isEmpty())v.address=query;out.add(v);
        }return out;
    }
    static final class Result {
        List<Data.Place> stops; List<Data.Place> companies;
        JSONObject geometry; JSONArray legs;
        double meters, seconds; int consultation;
        boolean exact;
    }
    Result calculate(Data.Place start,List<Data.Place> companies,Data.Place end,int mode) throws Exception {
        int n=companies.size();if(n<1||n>40)throw new IOException("한 번에 1~40개 업체를 선택해 주세요.");
        List<Data.Place> nodes=new ArrayList<>();nodes.add(start);nodes.addAll(companies);
        int endIndex=0;if(mode==2){endIndex=nodes.size();nodes.add(end);}
        JSONObject matrix=get(data.endpoint("routing")+"/table/v1/driving/"+coordinates(nodes)+"?annotations=duration",86400000);
        if(!"Ok".equals(matrix.optString("code")))throw new IOException("도로 이동시간을 계산하지 못했습니다: "+matrix.optString("message",matrix.optString("code")));
        JSONArray rows=matrix.getJSONArray("durations"); double[][] costs=new double[nodes.size()][nodes.size()];
        for(int i=0;i<nodes.size();i++)for(int k=0;k<nodes.size();k++)costs[i][k]=rows.getJSONArray(i).isNull(k)?Double.POSITIVE_INFINITY:rows.getJSONArray(i).getDouble(k);
        List<Integer> order=n<=11?exact(costs,n,mode==1?-1:endIndex):heuristic(costs,n,mode==1?-1:endIndex);
        if(order==null)throw new IOException("연결되지 않는 도로 구간이 있습니다. 출발지·업체 좌표를 확인해 주세요.");
        Result r=new Result();r.exact=n<=11;r.stops=new ArrayList<>();r.companies=new ArrayList<>();r.stops.add(start);
        for(int i:order){Data.Place p=nodes.get(i);r.stops.add(p);r.companies.add(p);r.consultation+=p.minutes;}
        if(mode!=1)r.stops.add(nodes.get(endIndex));
        JSONObject response=get(data.endpoint("routing")+"/route/v1/driving/"+coordinates(r.stops)+"?overview=full&geometries=geojson&steps=false",86400000);
        if(!"Ok".equals(response.optString("code")))throw new IOException("도로 경로를 불러오지 못했습니다.");
        JSONObject route=response.getJSONArray("routes").getJSONObject(0);r.geometry=route.getJSONObject("geometry");r.legs=route.getJSONArray("legs");
        r.meters=route.getDouble("distance");r.seconds=route.getDouble("duration");return r;
    }
    static String coordinates(List<Data.Place> p){StringJoiner s=new StringJoiner(";");for(Data.Place v:p)s.add(v.lon+","+v.lat);return s.toString();}
    static List<Integer> exact(double[][] c,int n,int end) {
        int size=1<<n;double[][] dp=new double[size][n];int[][] prev=new int[size][n];
        for(int m=0;m<size;m++){Arrays.fill(dp[m],Double.POSITIVE_INFINITY);Arrays.fill(prev[m],-1);}
        for(int j=0;j<n;j++)dp[1<<j][j]=c[0][j+1];
        for(int m=1;m<size;m++)for(int j=0;j<n;j++)if((m&(1<<j))!=0&&Double.isFinite(dp[m][j]))
            for(int k=0;k<n;k++)if((m&(1<<k))==0){int next=m|(1<<k);double value=dp[m][j]+c[j+1][k+1];if(value<dp[next][k]){dp[next][k]=value;prev[next][k]=j;}}
        double best=Double.POSITIVE_INFINITY;int last=-1;
        for(int j=0;j<n;j++){double v=dp[size-1][j]+(end<0?0:c[j+1][end]);if(v<best){best=v;last=j;}}
        if(last<0)return null;List<Integer> order=new ArrayList<>();int mask=size-1;
        while(last>=0){order.add(last+1);int next=prev[mask][last];mask^=1<<last;last=next;}Collections.reverse(order);return order;
    }
    static double score(List<Integer> a,double[][] c,int end){double v=0;int p=0;for(int k:a){v+=c[p][k];p=k;}return v+(end<0?0:c[p][end]);}
    static List<Integer> heuristic(double[][] c,int n,int end){
        List<Integer> best=null;double value=Double.POSITIVE_INFINITY;
        // Multiple starting points and full directed-cost evaluation: valid for one-way roads.
        for(int first=1;first<=n;first++){
            List<Integer> a=new ArrayList<>();boolean[] used=new boolean[n+1];a.add(first);used[first]=true;
            while(a.size()<n){int next=-1;double nearest=Double.POSITIVE_INFINITY;int p=a.get(a.size()-1);
                for(int j=1;j<=n;j++)if(!used[j]&&c[p][j]<nearest){nearest=c[p][j];next=j;}if(next<0)break;a.add(next);used[next]=true;}
            if(a.size()!=n)continue;
            double current=score(a,c,end);
            for(int pass=0;pass<8;pass++){boolean changed=false;
                for(int i=0;i<n-1;i++)for(int j=i+1;j<n;j++){
                    Collections.reverse(a.subList(i,j+1));double s=score(a,c,end);
                    if(s+0.01<current){current=s;changed=true;}else Collections.reverse(a.subList(i,j+1));
                }if(!changed)break;
            }
            if(current<value){best=new ArrayList<>(a);value=current;}
        }return best;
    }
}
