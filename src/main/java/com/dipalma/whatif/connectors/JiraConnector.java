package com.dipalma.whatif.connectors;

import com.dipalma.whatif.model.JiraTicket;
import com.dipalma.whatif.model.ProjectRelease;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter; // Import the formatter
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class JiraConnector {
    private final String projectKey;
    private static final String JIRA_URL = "https://issues.apache.org/jira";
    // Define a formatter that matches JIRA's date format (e.g., "2009-04-01T15:59:07.000+0000")
    private static final DateTimeFormatter JIRA_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");


    public JiraConnector(String projectKey) {
        this.projectKey = projectKey;
    }

    public List<ProjectRelease> getProjectReleases() throws IOException {
        String url = String.format("%s/rest/api/2/project/%s/versions", JIRA_URL, projectKey);
        String jsonResponse = sendGetRequest(url);

        JSONArray versions = new JSONArray(jsonResponse);
        List<ProjectRelease> releases = new ArrayList<>();
        for (int i = 0; i < versions.length(); i++) {
            JSONObject version = versions.getJSONObject(i);
            // Ensure the version is released and has a release date
            if (version.optBoolean("released") && version.has("releaseDate")) {
                LocalDate releaseDate = LocalDate.parse(version.getString("releaseDate"));
                releases.add(new ProjectRelease(version.getString("name"), releaseDate, 0));
            }
        }

        // Sort releases chronologically and assign a 1-based index
        releases.sort(Comparator.naturalOrder());
        return IntStream.range(0, releases.size())
                .mapToObj(i -> new ProjectRelease(releases.get(i).name(), releases.get(i).releaseDate(), i + 1))
                .collect(Collectors.toList());
    }

    public List<JiraTicket> getBugTickets() throws IOException {
        List<JiraTicket> tickets = new ArrayList<>();
        int startAt = 0;
        int maxResults = 100;
        boolean isLast = false;

        while (!isLast) {
            String jql = String.format("project = '%s' AND issuetype = Bug AND status in (Resolved, Closed) AND resolution = Fixed ORDER BY created ASC", projectKey);
            String url = String.format("%s/rest/api/2/search?jql=%s&fields=key,created,resolutiondate,versions&startAt=%d&maxResults=%d",
                    JIRA_URL, URLEncoder.encode(jql, StandardCharsets.UTF_8), startAt, maxResults);

            String jsonResponse = sendGetRequest(url);
            JSONObject response = new JSONObject(jsonResponse);
            JSONArray issues = response.getJSONArray("issues");

            for (int i = 0; i < issues.length(); i++) {
                JSONObject issue = issues.getJSONObject(i);
                JSONObject fields = issue.getJSONObject("fields");
                String key = issue.getString("key");

                // *** FIX IS HERE ***
                // Use the custom formatter to parse the date string
                String createdString = fields.getString("created");
                LocalDateTime created = ZonedDateTime.parse(createdString, JIRA_DATE_FORMATTER).toLocalDateTime();

                // Get affected versions, if any
                List<String> affectedVersions = new ArrayList<>();
                if (fields.has("versions")) {
                    JSONArray avs = fields.getJSONArray("versions");
                    for (int j = 0; j < avs.length(); j++) {
                        affectedVersions.add(avs.getJSONObject(j).getString("name"));
                    }
                }
                tickets.add(new JiraTicket(key, created, affectedVersions));
            }

            int total = response.getInt("total");
            startAt += issues.length();
            if (startAt >= total) {
                isLast = true;
            }
        }
        return tickets;
    }

    private String sendGetRequest(String url) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                return EntityUtils.toString(response.getEntity());
            }
        }
    }
}