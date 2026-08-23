package com.hechang.insighthub.service;

/** Safe rendering of immutable report snapshots. */
public interface ReportExportService {

    byte[] html(String markdown, String title);

    byte[] pdf(String markdown, String title);
}
