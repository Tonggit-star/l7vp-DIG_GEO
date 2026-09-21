package bingocloud.daas.customized.testtoekn;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class SimpleDaasDmsService {

    @Autowired
    private RestTemplate restTemplate;


    public Map<String, Object> query() {
        String token = getSsoToken();

        String url = "http://10.16.1.6:8081/daasDMS/ssoapi/GetResourceData/d2f77382-9f35-49e1-9792-e13d15908a17";

        // 组装请求体Map（和前端结构完全一致）
        Map<String, Object> pageParam = new HashMap<>();
        pageParam.put("limit", 1000);
        pageParam.put("pageIndex", 1);
        pageParam.put("sortField", "sex");
        pageParam.put("sortType", "desc");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("rowType", "list");
        requestBody.put("pageParam", pageParam);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        headers.set("spaceId", "79503c5c0eca4d159d051fc51a86cbed");
        headers.set("scopeType", "Space");

        // 修复：传入组装好的requestBody实例，而非HashMap.class
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        log.info("接口返回原始报文：{}", response.getBody());
        return JSON.parseObject(response.getBody(), Map.class);
    }

    private String getSsoToken() {
        String ssoUrl = UriComponentsBuilder.fromHttpUrl(
                        "http://10.16.1.6:8081/sso/oauth2/token")
                .queryParam("username", "bin")
                .queryParam("password", "bingocc")
                .queryParam("grant_type", "password")
                .queryParam("client_id", "clientId")
                .queryParam("client_secret", "clientSecret")
                .toUriString();

        ResponseEntity<String> resp = restTemplate.getForEntity(ssoUrl, String.class);
        String respBody = resp.getBody();
        log.info("获取token返回：{}", respBody);
        // 解析取出access_token
        Map<String, Object> tokenMap = JSON.parseObject(respBody, Map.class);
        return tokenMap.get("access_token").toString();
    }

    // ====================== main 本地测试入口（无需Spring容器，纯OkHttp测试） ======================
    public static void main(String[] args) throws Exception {
        // 引入okhttp依赖才能运行main测试
        // <dependency>
        //     <groupId>com.squareup.okhttp3</groupId>
        //     <artifactId>okhttp</artifactId>
        //     <version>4.12.0</version>
        // </dependency>
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.RequestBody emptyBody = okhttp3.RequestBody.create(null, new byte[0]);
        // 1. 获取token
        String ssoUrl = "http://10.16.1.6:8081/sso/oauth2/token?username=bingocc&password=bingocc@4321&grant_type=password&client_id=clientId&client_secret=clientSecret";
        okhttp3.Request tokenReq = new okhttp3.Request.Builder().url(ssoUrl).post( emptyBody).build();
        String tokenRespStr;
        try (okhttp3.Response tokenResp = client.newCall(tokenReq).execute()) {
            tokenRespStr = tokenResp.body().string();
        }
        Map<String, Object> tokenResult = JSON.parseObject(tokenRespStr, Map.class);
        String accessToken = tokenResult.get("access_token").toString();
        String authHeader = "Bearer " + accessToken;
        System.out.println("获取到Token：" + accessToken);

        // 2. 组装请求体
        Map<String, Object> pageParam = new HashMap<>();
        pageParam.put("limit", 1000);
        pageParam.put("pageIndex", 1);
        pageParam.put("sortField", "sex");
        pageParam.put("sortType", "desc");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("rowType", "list");
        requestBody.put("pageParam", pageParam);
        String jsonBody = JSON.toJSONString(requestBody);

        // 3. 请求头
        okhttp3.Headers headers = new okhttp3.Headers.Builder()
                .add("Content-Type", "application/json; charset=utf-8")
                .add("Authorization", authHeader)
                .add("spaceId", "79503c5c0eca4d159d051fc51a86cbed")
                .add("scopeType", "Space")
                .build();

        // 4. 发起POST请求
        String apiUrl = "http://10.16.1.6:8081/daasDMS/ssoapi/ApiDataResource/ces1";
        okhttp3.MediaType mediaType = okhttp3.MediaType.get("application/json; charset=utf-8");
        okhttp3.RequestBody body = okhttp3.RequestBody.create(mediaType, jsonBody);
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(apiUrl)
                .headers(headers)
                .post(body)
                .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            String result = response.body().string();
            System.out.println("接口返回数据：");
            System.out.println(JSON.toJSONString(JSON.parseObject(result), JSON.toJSONString(JSONObject.class)));
        }
    }
}
