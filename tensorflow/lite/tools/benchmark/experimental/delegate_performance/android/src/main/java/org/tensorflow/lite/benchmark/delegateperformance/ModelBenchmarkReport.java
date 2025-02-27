/* Copyright 2023 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/
package org.tensorflow.lite.benchmark.delegateperformance;

import static org.tensorflow.lite.benchmark.delegateperformance.DelegatePerformanceBenchmark.checkState;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Model-level benchmark report class to be extended by {@link AccuracyBenchmarkReport} and {@link
 * LatencyBenchmarkReport}.
 *
 * <p>This class contains helper functions to convert the raw performance results from the native
 * layer into metric-level, delegate-level and model-level pass status values.
 *
 * <p>TODO(b/250877013): Add concrete implementation.
 */
public abstract class ModelBenchmarkReport<ResultsT> implements ModelBenchmarkReportInterface {
  private static final String TAG = "ModelBenchmarkReport";

  protected final String modelName;
  /* Map from performance metric names to the maximum regression percentage thresholds allowed. */
  protected final Map<String, Float> maxRegressionPercentageAllowed = new HashMap<>();
  /**
   * List of {@link RawDelegateMetricsEntry}, which stores delegate-level performance results
   * collected from the native layer.
   */
  protected final List<RawDelegateMetricsEntry> rawDelegateMetrics = new ArrayList<>();
  /**
   * List of {@link DelegateMetricsEntry}, which stores delegate-level performance results computed
   * by {@link #computeModelReport()}.
   */
  protected final List<DelegateMetricsEntry> processedDelegateMetrics = new ArrayList<>();
  /** Model-level pass status. The field will be updated by {@link #computeModelReport()}. */
  protected BenchmarkResultType result = BenchmarkResultType.UNKNOWN;

  protected ModelBenchmarkReport(String modelName) {
    this.modelName = modelName;
  }

  /**
   * Parses accuracy or latency results into the unified {@link RawDelegateMetricsEntry} format for
   * further processing.
   */
  public abstract void addResults(ResultsT results, TfLiteSettingsListEntry entry);

  @Override
  public String modelName() {
    return modelName;
  }

  @Override
  public List<DelegateMetricsEntry> processedDelegateMetrics() {
    return Collections.unmodifiableList(processedDelegateMetrics);
  }

  @Override
  public BenchmarkResultType result() {
    return result;
  }

  /**
   * Converts the prepopulated list of {@link RawDelegateMetricsEntry}, the raw performance results
   * collected from the native layer, into a list of {@link DelegateMetricsEntry}.
   *
   * <p>Note: {@link #addResults(ResultsT, TfLiteSettingsListEntry)} should be called to populate
   * the list of {@link RawDelegateMetricsEntry} before calling this method.
   *
   * <p>TODO(b/268595172): Remove the above precondition.
   */
  @Override
  public void computeModelReport() {
    if (!processedDelegateMetrics.isEmpty()) {
      // Metrics are computed.
      Log.i(TAG, "Delegate metrics are already computed. Skips the computation.");
      return;
    }
    // At least 2 delegates (the default delegate and the test target delegate) are tested.
    checkState(rawDelegateMetrics.size() >= 2);
    // The test target delegate is the last delegate to benchmark. The order will be guaranteed by
    // DelegatePerformanceBenchmark#loadTfLiteSettingsList().
    RawDelegateMetricsEntry testTarget = rawDelegateMetrics.get(rawDelegateMetrics.size() - 1);
    checkState(testTarget.isTestTarget());
    // Use {@code LinkedHashMap} to keep the insertion order.
    Map<String, MetricsEntry> metrics = new LinkedHashMap<>();
    for (String metricName : testTarget.metrics().keySet()) {
      metrics.put(
          metricName,
          MetricsEntry.create(
              testTarget.metrics().get(metricName), "N/A", BenchmarkResultType.NOT_APPLICABLE));
    }
    processedDelegateMetrics.add(
        DelegateMetricsEntry.create(
            testTarget.delegateIdentifier(), metrics, BenchmarkResultType.NOT_APPLICABLE));

    // Processes the reference delegate results. Compute the performance regressions by comparing
    // them with the results from the test target delegate.
    List<BenchmarkResultType> referenceResults = new ArrayList<>();
    for (RawDelegateMetricsEntry entry :
        rawDelegateMetrics.subList(0, rawDelegateMetrics.size() - 1)) {
      Map<String, MetricsEntry> referenceMetrics = new LinkedHashMap<>();
      List<BenchmarkResultType> metricResults = new ArrayList<>();
      for (String metricName : testTarget.metrics().keySet()) {
        MetricsEntry metricEntry =
            computeReferenceMetricEntry(
                entry.metrics().get(metricName), testTarget.metrics().get(metricName), metricName);
        referenceMetrics.put(metricName, metricEntry);
        // Filters for the metrics that are involved in the Pass/Pass with Warning/Fail result
        // generation.
        if (metricEntry.result() != BenchmarkResultType.NOT_APPLICABLE) {
          metricResults.add(metricEntry.result());
        }
      }
      // TODO(b/267488243): Load the delegate name from the native layer.
      boolean sameDelegateType = entry.delegateName().equals(testTarget.delegateName());
      BenchmarkResultType referenceDelegateResult =
          DelegatePerformanceBenchmark.aggregateResults(sameDelegateType, metricResults);
      referenceResults.add(referenceDelegateResult);
      processedDelegateMetrics.add(
          DelegateMetricsEntry.create(
              entry.delegateIdentifier(), referenceMetrics, referenceDelegateResult));
    }
    result = DelegatePerformanceBenchmark.aggregateResults(/* strict= */ true, referenceResults);
  }

  @Override
  public JSONObject toJsonObject() throws JSONException {
    JSONObject jsonObject = new JSONObject();
    jsonObject.put("model", modelName);
    jsonObject.put("result", result.toString());
    JSONArray processedDelegateMetricsArray = new JSONArray();
    for (DelegateMetricsEntry entry : processedDelegateMetrics) {
      processedDelegateMetricsArray.put(entry.toJsonObject());
    }
    jsonObject.put("metrics", processedDelegateMetricsArray);
    JSONArray rawDelegateMetricsArray = new JSONArray();
    for (RawDelegateMetricsEntry entry : rawDelegateMetrics) {
      rawDelegateMetricsArray.put(entry.toJsonObject());
    }
    jsonObject.put("raw_metrics", rawDelegateMetricsArray);
    JSONObject maxRegressionPercentageAllowedObject = new JSONObject();
    for (Map.Entry<String, Float> entry : maxRegressionPercentageAllowed.entrySet()) {
      maxRegressionPercentageAllowedObject.put(entry.getKey(), entry.getValue());
    }
    jsonObject.put("max_regression_percentage_allowed", maxRegressionPercentageAllowedObject);
    return jsonObject;
  }

  private MetricsEntry computeReferenceMetricEntry(
      Float referenceValue, Float testTargetValue, String metricName) {
    boolean checkRegression = maxRegressionPercentageAllowed.containsKey(metricName);
    String regression = "N/A";
    BenchmarkResultType result =
        checkRegression ? BenchmarkResultType.FAIL : BenchmarkResultType.NOT_APPLICABLE;
    if (referenceValue == null || testTargetValue == null) {
      return MetricsEntry.create(referenceValue, regression, result);
    }
    // Here is a mitigation to the lack of support for criteria operators. "ok" metric is handled
    // specifically for the accuracy benchmarking results.
    // TODO(b/267313326): remove the mitigation after the criteria operators are added.
    if (metricName.equals("ok")) {
      if (testTargetValue == AccuracyBenchmarkReport.PASS) {
        // The test target delegate passed the accuracy checks.
        result = BenchmarkResultType.PASS;
      } else if (referenceValue == AccuracyBenchmarkReport.FAIL) {
        // Both the test target and the reference delegates failed the accuracy checks. Therefore,
        // it is not considered as a regression.
        result = BenchmarkResultType.PASS_WITH_WARNING;
      }
      return MetricsEntry.create(referenceValue, regression, result);
    }

    // Here we assume that lower values of the metric are better, for all of our metrics.
    // TODO(b/267313326): Remove the assumption with the criteria operator support.
    float regressionValue = 0f;
    if (!testTargetValue.equals(referenceValue)) {
      regressionValue = (testTargetValue - referenceValue) / referenceValue;
    }
    if (checkRegression) {
      if (regressionValue <= 0) {
        result = BenchmarkResultType.PASS;
      } else if (regressionValue <= maxRegressionPercentageAllowed.get(metricName) / 100f) {
        result = BenchmarkResultType.PASS_WITH_WARNING;
      }
    }
    regression = toPercentage(regressionValue);
    return MetricsEntry.create(referenceValue, regression, result);
  }

  private String toPercentage(float n) {
    return String.format(Locale.ENGLISH, "%.1f", n * 100) + "%";
  }
}
