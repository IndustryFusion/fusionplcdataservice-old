/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.fusion.fusionplcdataservice.service;

import io.fusion.core.FusionDataServiceConfig;
import io.fusion.core.FusionDataServiceConfig.FieldSpec;
import io.fusion.core.exception.ConnectionException;
import io.fusion.core.exception.JobNotFoundException;
import io.fusion.core.exception.ReadException;
import io.fusion.core.source.MetricsPullService;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.PlcDriverManager;
import org.apache.plc4x.java.api.exceptions.PlcConnectionException;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.utils.connectionpool.PooledPlcDriverManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@Primary
public class PlcDataService implements MetricsPullService {
    private final PlcDriverManager plcDriverManager;
    private final FusionDataServiceConfig fusionDataServiceConfig;

    @Autowired
    public PlcDataService(FusionDataServiceConfig fusionDataServiceConfig) {
        this.fusionDataServiceConfig = fusionDataServiceConfig;
        // We're using the pooled version of the PlcDriverManager (This keeps the connection open)
        this.plcDriverManager = new PooledPlcDriverManager();
    }

    @Override
    public Map<String, String> getMetrics(String jobId) {
        log.info("Fetching metrics for job {}", jobId);
        var jobSpec = fusionDataServiceConfig.getJobSpecs().get(jobId);
        if (jobSpec == null) {
            throw new JobNotFoundException();
        }
        Map<String, String> data = new HashMap<>();
        try (var plcConnection = plcDriverManager.getConnection(
                fusionDataServiceConfig.getConnectionString())) {
            // First check we have a configuration for the given job id.
            final List<FieldSpec> fieldSpecs =
                    jobSpec.getFields();
            if (fieldSpecs == null) {
                throw new JobNotFoundException();
            }

            // Prepare a read-request for this job.
            var readRequestBuilder = plcConnection.readRequestBuilder();
            for (FieldSpec fieldSpec : fieldSpecs) {
                readRequestBuilder.addItem(fieldSpec.getTarget(), fieldSpec.getSource());
            }
            final PlcReadRequest readRequest = readRequestBuilder.build();

            // Execute the read-request.
            final PlcReadResponse readResponse = readRequest.execute().get();

            // Convert the result into a data structure Industry Fusion can understand.
            for (String fieldName : readResponse.getFieldNames()) {
                var responseCode = readResponse.getResponseCode(fieldName);
                if (responseCode == PlcResponseCode.OK) {
                    data.put(fieldName, readResponse.getObject(fieldName).toString());
                } else {
                    log.warn("Plc response for field {}: {}",fieldName, responseCode);
                }
            }
        } catch (JobNotFoundException e) {
            throw new JobNotFoundException();
        } catch (PlcConnectionException e) {
            log.warn("Exception: ", e);
            throw new ConnectionException();
        } catch (InterruptedException e) {
            log.error("InterruptedException: ", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Exception: ", e);
            throw new ReadException();
        }
        log.info("Fetched {} metrics for job {}", data.size(), jobId);
        return data;
    }
}
