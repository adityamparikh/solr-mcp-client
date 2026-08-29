package org.apache.solr.mcp.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SolrMcpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolrMcpClientApplication.class, args);
    }
}
