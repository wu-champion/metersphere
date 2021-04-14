package io.metersphere.performance.service;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.nacos.client.utils.StringUtils;
import io.metersphere.base.domain.LoadTestReportWithBLOBs;
import io.metersphere.base.domain.LoadTestWithBLOBs;
import io.metersphere.base.mapper.LoadTestMapper;
import io.metersphere.base.mapper.LoadTestReportMapper;
import io.metersphere.commons.exception.MSException;
import io.metersphere.commons.utils.DateUtils;
import io.metersphere.commons.utils.LogUtil;
import io.metersphere.performance.base.ReportTimeInfo;
import io.metersphere.performance.controller.request.MetricDataRequest;
import io.metersphere.performance.controller.request.MetricQuery;
import io.metersphere.performance.controller.request.MetricRequest;
import io.metersphere.performance.dto.MetricData;
import io.metersphere.performance.dto.Monitor;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
@Transactional(rollbackFor = Exception.class)
public class MetricQueryService {


    private String prometheusHost = "http://192.168.1.8:9090";

    @Resource
    private RestTemplate restTemplate;
    @Resource
    private LoadTestReportMapper loadTestReportMapper;
    @Resource
    private LoadTestMapper loadTestMapper;
    @Resource
    private ReportService reportService;


    public List<MetricData> queryMetricData(MetricRequest metricRequest) {
        List<MetricData> metricDataList = new ArrayList<>();
        long endTime = metricRequest.getEndTime();
        long startTime = metricRequest.getStartTime();
        int step = metricRequest.getStep();
        long reliableEndTime;
        if (endTime > System.currentTimeMillis()) {
            reliableEndTime = System.currentTimeMillis();
        } else {
            reliableEndTime = endTime;
        }

        Optional.ofNullable(metricRequest.getMetricDataQueries()).ifPresent(metricDataQueries -> metricDataQueries.forEach(query -> {
            String promQL = query.getPromQL();
            promQL = String.format(promQL, query.getInstance());
            if (StringUtils.isEmpty(promQL)) {
                MSException.throwException("promQL is null");
            } else {
                Optional.of(queryPrometheusMetric(promQL, query.getSeriesName(), startTime, reliableEndTime, step, query.getInstance())).ifPresent(metricDataList::addAll);
            }
        }));

        return metricDataList;
    }
    

    private List<MetricData> queryPrometheusMetric(String promQL, String seriesName, long startTime, long endTime, int step, String instance) {
        DecimalFormat df = new DecimalFormat("#.###");
        String start = df.format(startTime / 1000.0);
        String end = df.format(endTime / 1000.0);
        JSONObject response = restTemplate.getForObject(prometheusHost + "/api/v1/query_range?query={promQL}&start={start}&end={end}&step={step}", JSONObject.class, promQL, start, end, step);
        return handleResult(seriesName, response, instance);
    }

    private List<MetricData> handleResult(String seriesName, JSONObject response, String instance) {
        List<MetricData> list = new ArrayList<>();

        Map<String, Set<String>> labelMap = new HashMap<>();

        if (response != null && StringUtils.equals(response.getString("status"), "success")) {
            JSONObject data = response.getJSONObject("data");
            JSONArray result = data.getJSONArray("result");

            if (result.size() > 1) {
                result.forEach(rObject -> {
                    JSONObject resultObject = new JSONObject((Map)rObject);
//                    JSONObject resultObject = JSONObject.parseObject(rObject.toString());
                    JSONObject metrics = resultObject.getJSONObject("metric");

                    if (metrics != null && metrics.size() > 0) {
                        for (Map.Entry<String, Object> entry : metrics.entrySet())
                            labelMap.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).add(entry.getValue().toString());
                    }
                });
            }

            Optional<String> uniqueLabelKey = labelMap.entrySet().stream().filter(entry -> entry.getValue().size() == result.size()).map(Map.Entry::getKey).findFirst();

            result.forEach(rObject -> {
                MetricData metricData = new MetricData();
                List<String> timestamps = new ArrayList<>();
                List<Double> values = new ArrayList<>();

                JSONObject resultObject = new JSONObject((Map)rObject);
                JSONObject metrics = resultObject.getJSONObject("metric");
                JSONArray jsonArray = resultObject.getJSONArray("values");
                jsonArray.forEach(value -> {
                    JSONArray ja = JSONObject.parseArray(value.toString());
                    Double timestamp = ja.getDouble(0);
                    try {
                        timestamps.add(DateUtils.getTimeString((long) (timestamp * 1000)));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    values.add(ja.getDouble(1));
                });

                if (CollectionUtils.isNotEmpty(values)) {
                    metricData.setValues(values);
                    metricData.setTimestamps(timestamps);
                    metricData.setSeriesName(seriesName);
                    metricData.setInstance(instance);
                    uniqueLabelKey.ifPresent(s -> metricData.setUniqueLabel(metrics.getString(s)));
                    list.add(metricData);
                }
            });


        }

        return list;
    }

    public List<MetricData> queryMetric(String reportId) {
        LoadTestReportWithBLOBs report = loadTestReportMapper.selectByPrimaryKey(reportId);
        String testId = report.getTestId();
        LoadTestWithBLOBs loadTestWithBLOBs = loadTestMapper.selectByPrimaryKey(testId);
        String advancedConfiguration = loadTestWithBLOBs.getAdvancedConfiguration();
        JSONObject jsonObject = JSON.parseObject(advancedConfiguration);
        JSONArray monitorParams = jsonObject.getJSONArray("monitorParams");
        if (monitorParams == null) {
            return new ArrayList<>();
        }
        List<MetricDataRequest> list = new ArrayList<>();
        for (int i = 0; i < monitorParams.size(); i++) {
            Monitor monitor = monitorParams.getObject(i, Monitor.class);
            String instance = monitor.getIp() + ":" + monitor.getPort();
            getRequest(instance, list);
        }

        ReportTimeInfo reportTimeInfo = reportService.getReportTimeInfo(reportId);
        MetricRequest metricRequest = new MetricRequest();
        metricRequest.setMetricDataQueries(list);
        try {
            SimpleDateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            Date startTime = df.parse(reportTimeInfo.getStartTime());
            Date endTime = df.parse(reportTimeInfo.getEndTime());
            metricRequest.setStartTime(startTime.getTime());
            metricRequest.setEndTime(endTime.getTime());
        } catch (Exception e) {
            LogUtil.error(e, e.getMessage());
            e.printStackTrace();
        }

        return queryMetricData(metricRequest);
    }

    private void getRequest(String instance, List<MetricDataRequest> list) {
        Map<String, String> map = MetricQuery.getMetricQueryMap();
        Set<String> set = map.keySet();
        set.forEach(s -> {
            MetricDataRequest request = new MetricDataRequest();
            String promQL = map.get(s);
            request.setPromQL(promQL);
            request.setSeriesName(s);
            request.setInstance(instance);
            list.add(request);
        });
    }
}
