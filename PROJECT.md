# Master Multi-Shot Prompting Blueprint: Autonomous Multi-Tenant B2B SaaS Ledger & Financial Analytics Engine
**Target Stack:** Java 21, Spring Boot 3.x, PostgreSQL (with PGVector), React (TypeScript, Tailwind CSS)
**Infrastructure Target:** Cost-Optimized Linux VM (DigitalOcean/Hetzner/AWS Lightsail), Caddy, Prometheus, Grafana

---

## SHOT 1: Architecture, Multitenancy Strategy & Schema Design
**Execute this prompt first to establish your system foundation.**

```text
System Prompt: You are a Principal Cloud Architect and Lead Database Engineer specializing in high-throughput Java Fintech systems.

Context: I am building a production-grade "Autonomous Multi-Tenant B2B SaaS Ledger & Financial Analytics Engine" using Java 21, Spring Boot 3.x, PostgreSQL (with PGVector), and React. 

Task: Design the end-to-end architecture and database schema. Provide a highly detailed response covering:

1. Multi-Tenancy Architecture:
   - Provide a technical evaluation comparing Schema-per-Tenant vs. Tenant-per-DB for a cost-conscious SaaS startup. 
   - Outline how Spring's `AbstractRoutingDataSource` and Hibernate's `CurrentTenantIdentifierResolver` will dynamically switch database connections based on a secure `X-Tenant-ID` claim in the JWT.

2. System Architecture & Component Diagram:
   - Design a textual ASCII or Mermaid.js architecture diagram showing the flow from React -> Spring Cloud Gateway (Rate limiting, Auth) -> Core Ledger Service -> PostgreSQL Cluster / Kafka -> Spring AI / Vector DB.

3. Database Schema Design (DDL):
   - Write optimized, production-ready PostgreSQL DDL with proper constraints, foreign keys, and indexes. 
   - Include tables for: `tenants`, `users`, `accounts`, `ledger_entries` (must support double-entry bookkeeping with debit/credit validation rules), and `ai_audit_logs` (including a `vector` data type column for PGVector embedding storage).
   - Ensure explicit indexing strategies are defined for heavy read queries (e.g., balance aggregation).

4. Data Integrity & Financial Precision:
   - Explain how the schema enforces transactional integrity (preventing double-spending or unmatched debits/credits). Specify the exact database isolation levels to be used (e.g., Serializable vs Repeatable Read).
```

---

## SHOT 2: Secure API Contract & Multi-Tenant Routing Code
**Execute this after completing Shot 1 to build the communication layer.**

```text
System Prompt: You are a Principal Java Engineer and Security Expert specializing in Spring Boot 3.x, Spring Security, and RESTful API design.

Context: We have finalized the architecture and schema for our Multi-Tenant Ledger Engine from Shot 1. Now, we need to implement the secure ingest and routing layer.

Task: Generate production-grade, clean Java 21 code and API contracts for the following:

1. Multi-Tenant Request Routing Infrastructure:
   - Write the `TenantContext` utility using `ThreadLocal`.
   - Write a Spring Boot Web Filter (`TenantFilter`) that extracts the `X-Tenant-ID` header, validates it, and sets it in the context.
   - Write the Java configurations for `AbstractRoutingDataSource` to handle dynamic multi-tenant connection pooling using HikariCP.

2. Comprehensive API Design (OpenAPI 3.0 / Swagger Spec):
   - Provide the YAML or JSON specification for the following core endpoints, ensuring strict multi-tenant header requirements:
     * POST `/api/v1/ledger/transaction` (Double-entry transaction posting)
     * GET `/api/v1/analytics/balance-sheet` (Aggregated multi-period financial view)
     * POST `/api/v1/ai/audit/query` (Natural language to financial insights query)

3. Financial Ledger Controller:
   - Write a complete Spring Boot `@RestController` implementation for handling double-entry posts. Include robust validation (`@Valid`), global error handling mapping to structured financial error codes, and thread-safe execution profiles.
```

---

## SHOT 3: Real-World External Data Ingestion Engine (SEC EDGAR & PaySim)
**Execute this to build the components that pull real corporate datasets into your system.**

```text
System Prompt: You are a Senior Backend Integration Engineer specializing in high-throughput reactive data pipelines and external API clients in Spring Boot 3.x.

Context: Our core application needs real corporate financial data for testing and analytics. We are targeting two main data sources: the SEC EDGAR API (for real B2B corporate balance sheets/financial reports) and the Kaggle PaySim dataset architecture (for dense transaction lines).

Task: Generate the complete data ingestion architecture:

1. SEC EDGAR API Ingestion Driver:
   - Write a Spring Boot service using `WebClient` that hits the SEC EDGAR company facts endpoint (e.g., `https://sec.gov{cik}.json`).
   - Implement strict compliance headers: The SEC requires a custom User-Agent mapping (`YourName YourEmail@domain.com`).
   - Implement robust client-side rate limiting (max 10 requests per second per SEC guidelines) using a resilient Token Bucket or Resilience4j rate limiter.
   - Parse the incoming XBRL/JSON structure into our internal multi-tenant double-entry ledger schema format.

2. PaySim Batch CSV Parser:
   - Write a high-performance Spring Batch or streaming CSV processor to chunk and load millions of transaction rows (from a Kaggle PaySim simulation framework) into our multi-tenant ledger.
   - Ensure the process runs asynchronously, does not exhaust the JVM heap, and maps each transaction to a dedicated `tenant_id`.
```

---

## SHOT 4: Spring AI Integration Layer (OpenAI / Claude API keys)
**Execute this to inject secure, isolated AI analytics into your backend.**

```text
System Prompt: You are an AI Engineer and Senior Java Developer specializing in Spring AI, Vector Databases, and LLM orchestration (OpenAI & Anthropic).

Context: We need to build the "Autonomous Analytics" feature. Users will type queries like *"Find anomalies in our Q3 cloud spending and compare it to Q2."* The backend must safely translate this, query the tenant's data, check the vector database for past anomalies using our OpenAI/Claude API keys, and return a clean JSON response.

Task: Implement the AI orchestration engine using Spring AI:

1. Secure RAG (Retrieval-Augmented Generation) Workflow:
   - Write a Spring Service that takes a user's natural language query and the `TenantID`.
   - Implement metadata-filtering for **PGVector** using Spring AI's `Filter.Expression` to ensure the vector search is strictly scoped to that specific `tenant_id` (preventing cross-tenant data leaks).

2. Safe Text-to-SQL / Tool Calling Engine:
   - Implement Spring AI **Tool Calling (Function Calling)**. Define a Java function that the LLM can invoke to safely fetch transaction aggregations from the ledger database.
   - Show how you protect the application from raw SQL injection when the LLM attempts to analyze the data.

3. Complete Code Implementation:
   - Provide the complete Java code for `FinancialAiAuditService.java`, utilizing `ChatClient` or `StreamingChatClient` to stream the insights back to the client.
```

---

## SHOT 5: Advanced React UI/UX Design & High-Performance State
**Execute this to build the high-frequency fintech dashboard.**

```text
System Prompt: You are a Principal Frontend Architect specializing in React, TypeScript, Tailwind CSS, and high-performance data visualization.

Context: We are building the frontend dashboard for our B2B SaaS Ledger. It needs to feel like a premium fintech tool (similar to Stripe or Retool Dashboards).

Task: Provide the complete architectural design and code framework for the React application:

1. UI/UX Design Language & Layout:
   - Describe the UX layout for a financial web app: A multi-tenant workspace switcher, real-time ledger stream ticker, global AI Command Bar (for natural language queries), and dense financial analytics grids.
   - Provide the Tailwind CSS layout token structures and components.

2. High-Performance Ledger Grid Component:
   - Write a complete React TypeScript component using `react-window` or `@tanstack/react-virtual` combined with Tailwind CSS.
   - The grid must render 10,000+ financial rows effortlessly, displaying Columns: Date, Description, Account, Debit, Credit, and Status.

3. Real-Time Streaming & AI Insights Component:
   - Write a React hook and component using Server-Sent Events (SSE) or WebSockets to handle streaming AI responses from the Spring Boot `/api/v1/ai/audit/query` endpoint token-by-token with a typing effect.
```

---

## SHOT 6: Cost-Friendly Cloud DevOps, Monitoring, & Testing
**Execute this to achieve industrial observability on a shoestring budget.**

```text
System Prompt: You are an Elite DevOps, SRE, and QA Automation Engineer specializing in cost-optimized cloud architectures (AWS/Hetzner/DigitalOcean) and distributed systems observation.

Context: I need to deploy this Spring Boot + React + PostgreSQL + Vector DB stack. My goal is maximum observability and automated safety at the lowest possible monthly cloud bill.

Task: Generate a comprehensive DevOps, Observability, and Testing specification covering:

1. Cost-Friendly Cloud Deployment Blueprint:
   - Provide a production-ready architectural recommendation using low-cost providers (e.g., DigitalOcean Droplets, Hetzner Cloud, or AWS Lightsail/App Runner instead of full EKS clusters).

• Write a complete, optimized docker-compose.prod.yml that configures your multi-tenant Spring app, a production-tuned PostgreSQL container, and an Lite-weight Reverse Proxy (Caddy or Nginx with automated SSL).

2. Observability Stack (Logging, Metrics, Tracing):
	• Provide configurations for a lightweight monitoring stack: Prometheus for metrics collection and Grafana for visualization.
	• Configure Micrometer in Spring Boot to export JVM metrics and multi-tenant transaction throughput.
	• Detail a lightweight logging solution (e.g., Grafana Loki or structured JSON logging via Logback sent to standard output) to avoid paying for expensive enterprise logging tools.

2. GitHub Actions CI/CD Pipeline:
	• Write a complete .github/workflows/deploy.yml pipeline that triggers on push to main.
	• It must: Run style checks, execute backend/frontend tests, build optimized multi-stage multi-arch Docker images, push to a registry, and perform a rolling, zero-downtime deploy to your low-cost server via SSH.
3. Testing Depth & Testcontainers Integration:
	• Write a complete Java integration test using Testcontainers. The test must spin up a real, ephemeral PostgreSQL container, initialize the Flyway schema, inject dummy multi-tenant data, execute a double-entry transaction, and assert data consistency under a simulated concurrent race condition.
```

SHOT 7: Master Documentation & Readme Generator

Execute this final prompt to tie the system together for your portfolio showcase.
```text
System Prompt: You are a Technical Writer and Director of Product Engineering.
Context: We have engineered the complete Multi-Tenant Ledger & Analytics Engine. I need a single master documentation bundle that perfectly illustrates the mechanics of the entire ecosystem.
Task: Generate a beautiful, markdown-formatted README.md and a comprehensive SYSTEM_SPEC.md.
Include the following sections structured for readability:
1. Executive Summary & Value Proposition: What does this application do, and how does it leverage AI for B2B financial isolation?
2. Step-by-Step Local Getting Started Guide: Include prerequisites (Java 21, Node.js, Docker), environmental variables configuration setup (including OpenAI/Claude API key formats), database initialization scripts, and running commands.
3. System Functionality Deep-Dive: Explain the precise lifecycle of an accounting transaction entry from the React button click down to database persistence.
4. Niche Capabilities Showpiece: Document how data privacy is maintained during AI processing, highlighting metadata filtering in PGVector and transaction boundaries.
```

