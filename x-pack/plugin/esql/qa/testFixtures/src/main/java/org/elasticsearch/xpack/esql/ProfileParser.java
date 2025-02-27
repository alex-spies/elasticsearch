/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql;

import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.json.JsonXContent;

import perfetto.protos.CounterDescriptor;
import perfetto.protos.ProcessDescriptor;
import perfetto.protos.ThreadDescriptor;
import perfetto.protos.Trace;
import perfetto.protos.TracePacket;
import perfetto.protos.TrackDescriptor;
import perfetto.protos.TrackEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ProfileParser {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        PluginManager.addPackage(LogConfigurator.class.getPackage().getName());
        LogConfigurator.configureESLogging();
        Logger logger = LogManager.getLogger(ProfileParser.class);

        if (args.length != 2) {
            throw new IllegalArgumentException("Requires input and output file names");
        }
        Path inputFileName = Path.of(args[0].replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath();
        Path outputFileName = Path.of(args[1].replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath();

        Map<String, Object> map;
        try (InputStream input = Files.newInputStream(inputFileName)) {
            logger.info("Starting to parse {}", inputFileName);
            map = XContentHelper.convertToMap(JsonXContent.jsonXContent, input, true);
            logger.info("Finished parsing", inputFileName);
        }

        Map<String, Object> profile = (Map<String, Object>) map.get("profile");
        List<Map<String, Object>> drivers = (List<Map<String, Object>>) profile.get("drivers");

        logger.info("Starting transformation into perfetto-compatible output format", args[0]);
        int driverIndex = 0;
        try (
            OutputStream output = Files.newOutputStream(outputFileName);
        ) {
            logger.info("Starting to write {}", outputFileName);

            var trace = Trace.newBuilder();

            trace.addPacket(processDescriptor(0, 0, "node"));
            int tid = 0;
            trace.addPacket(threadDescriptor(1, 0, tid, "driver"));

            trace.addPacket(sliceBegin(0, tid, "driver0track0"));
            trace.addPacket(sliceEnd(1000000, tid));

            trace.addPacket(sliceBegin(2000000, tid, "driver0track1"));
            trace.addPacket(sliceEnd(3000000, tid));

            trace.build().writeTo(output);

            logger.info("Finished writing to", outputFileName);
        }

        logger.info("Exiting", args[0]);
    }

    private static TracePacket sliceBegin(long timestamp, int trackUuid, String name) {
        var packet = TracePacket.newBuilder().setTimestamp(timestamp).setTrustedPacketSequenceId(0);

        var event = TrackEvent.newBuilder().setType(TrackEvent.Type.TYPE_SLICE_BEGIN).setTrackUuid(trackUuid).setName(name);

        return packet.setTrackEvent(event).build();
    }

    private static TracePacket sliceEnd(long timestamp, int trackUuid) {
        var packet = TracePacket.newBuilder().setTimestamp(timestamp).setTrustedPacketSequenceId(0);

        var event = TrackEvent.newBuilder().setType(TrackEvent.Type.TYPE_SLICE_END).setTrackUuid(trackUuid);

        return packet.setTrackEvent(event).build();
    }

    private static TracePacket processDescriptor(int uuid, int pid, String processName) {
        var processDescriptor = ProcessDescriptor.newBuilder().setPid(pid).setProcessName(processName).build();
        var processTrackDescriptor = TrackDescriptor.newBuilder().setUuid(uuid).setProcess(processDescriptor).build();

        return TracePacket.newBuilder().setTrackDescriptor(processTrackDescriptor).build();
    }

    private static TracePacket threadDescriptor(int uuid, int pid, int tid, String threadName) {
        var threadDescriptor = ThreadDescriptor.newBuilder().setPid(pid).setTid(tid).setThreadName(threadName).build();
        var processTrackDescriptor = TrackDescriptor.newBuilder().setUuid(uuid).setThread(threadDescriptor).build();

        return TracePacket.newBuilder().setTrackDescriptor(processTrackDescriptor).build();
    }

    private static TracePacket threadTimeDescriptor(int uuid) {
        var counterDescriptor = CounterDescriptor.newBuilder().setType(CounterDescriptor.BuiltinCounterType.COUNTER_THREAD_TIME_NS);
        var counterTrackDescriptor = TrackDescriptor.newBuilder().setUuid(uuid).setCounter(counterDescriptor);

        return TracePacket.newBuilder().setTrackDescriptor(counterTrackDescriptor).build();
    }

    @SuppressWarnings("unchecked")
    private static void parseDriverProfile(Map<String, Object> driver, int driverIndex, XContentBuilder builder) throws IOException {
        String taskDescription = (String) driver.get("task_description");
        String name = taskDescription + driverIndex;

        builder.startObject();
        builder.field("ph", "B");
        builder.field("name", name);
        builder.field("cat", taskDescription);
        builder.field("pid", 0);
        builder.field("tid", driverIndex);
        long startMicros = readIntOrLong(driver, "start_millis") * 1000;
        builder.field("ts", startMicros);
        double durationMicros = ((double) readIntOrLong(driver, "took_nanos")) / 1000.0;
        builder.field("dur", durationMicros);
        double cpuDurationMicros = ((double) readIntOrLong(driver, "cpu_nanos")) / 1000.0;
        builder.field("tdur", cpuDurationMicros);

        builder.field("args");
        builder.startObject();
        builder.field("cpu_nanos", readIntOrLong(driver, "cpu_nanos"));
        builder.field("took_nanos", readIntOrLong(driver, "took_nanos"));
        builder.field("iterations", readIntOrLong(driver, "iterations"));
        // TODO: Sleeps have more details
        int sleeps = ((Map<?, ?>) driver.get("sleeps")).size();
        builder.field("sleeps", sleeps);
        builder.field("operators");
        builder.startArray();
        for (Map<String, Object> operator : (List<Map<String, Object>>) driver.get("operators")) {
            builder.value((String) operator.get("operator"));
            // TODO: Add status; needs standardizing the operatur statuses, probably.
        }
        builder.endArray();
        builder.endObject();

        builder.endObject();


        /// End event
        builder.startObject();
        builder.field("ph", "E");
        builder.field("name", name);
        builder.field("pid", 0);
        builder.field("tid", driverIndex);
        builder.field("ts", readIntOrLong(driver, "stop_millis") * 1000);
        builder.endObject();
    }

    private static Long readIntOrLong(Map<String, Object> json, String name) {
        Object number = json.get(name);

        return number instanceof Long l ? l : ((Integer) number).longValue();
    }
}
