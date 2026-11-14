# Source Extract — Project 1 and Common Requirements ONLY

> Verbatim text extracted from user-provided `CSE444_Course_Projects(1).docx`. Other projects intentionally omitted. The original DOCX is authoritative if extraction differs.

## Project 1 — AgentGate

````text
Project 1 — AgentGate: Governance Gateway for AI Agents' Tool Calls
PROBLEM STATEMENT
Companies now let AI agents act on business systems through the Model Context Protocol (MCP). A support bot can look up an order, issue a refund or message a customer by calling a “tool”. Today each team connects its agents straight to its tool servers, so nobody can say which agent may call which tool, on whose behalf, how often, or what it actually did.
That gap blocks adoption. A support bot tricked by a prompt injection into calling a refund tool is a direct financial loss, so security teams often refuse to let agents near production systems. Developers, meanwhile, route around any control that is slow or painful to set up.
AgentGate is the single front door between agents and tools. Every tool call passes through it and is checked against policy, held for a human when risky, cleaned of sensitive data, limited in volume and recorded in a tamper-evident log. The project succeeds when a security reviewer can approve an agent rollout because every call is governed and explainable, and a developer can put a new tool behind the gateway in minutes.
FUNCTIONAL REQUIREMENTS
FR1	Tool registry. Register downstream MCP servers and plain REST endpoints as tools, each with a name, description, input schema, risk level (low, medium, high) and owning team. REST endpoints can be imported from an OpenAPI file. Agents see all permitted tools through one MCP endpoint.
FR2	Agent identity. Every agent authenticates as a registered client (OAuth2 client credentials). A call may also carry the end user the agent acts for, and policies can use both identities.
FR3	Policies. Allow or deny by agent, tool and argument conditions (for example, refunds up to 2,000 BDT, or reads limited to the requesting customer's own records). Default is deny. Policies are versioned, take effect within 10 seconds without a restart, and can run in dry-run mode that only logs what would be blocked.
FR4	Human approval. Calls to high-risk tools pause as pending approvals. An approver is notified (web page and webhook) and can approve, reject or edit the arguments. Unanswered requests are rejected after a configurable timeout, and the agent receives a clear result in every case.
FR5	Guards. Arguments are validated against the tool's schema before forwarding. Tool results are redacted according to the agent's clearance (e-mail addresses, Bangladeshi phone numbers, national ID numbers), and oversized results are cut with a notice.
FR6	Limits. Rate limits and daily quotas per agent and per tool, plus a blast-radius cap on write calls (for example, 20 per hour per agent). A breach blocks further calls and raises an alert.
FR7	Audit trail. Every call is recorded with agent, user, tool, redacted arguments, the policy rule that decided it, approval details, latency and outcome. Records are hash-chained so tampering is detectable, and they are searchable and exportable.
FR8	Kill switch. An administrator can disable one agent, one tool or all write tools instantly; pending approvals for them are cancelled.
FR9	Policy playground. Test a sample call against current or draft policies and see the decision with the rule that produced it.
FR10	Downstream health. Per-tool timeouts and circuit breakers; tools that are down are hidden from agents' tool lists until they recover.
QUALITY ATTRIBUTES (SUCCESS MEASURES)
QA1	Security. A red-team suite of 30 scripted malicious calls (injected refunds, cross-customer reads, argument smuggling) is 100% blocked or held for approval, while no more than 5% of 200 legitimate calls are wrongly blocked.
QA2	Performance. The gateway adds no more than 30 ms at p95 to a tool call at 100 calls per second, excluding approval waits.
QA3	Auditability. For any call, an auditor finds who made it, on whose behalf, what it did and which rule allowed it in under 1 minute; editing any stored audit row is detected by the verify command.
QA4	Fail-safe availability. If the policy store is unreachable, medium- and high-risk tools fail closed, while low-risk tools keep working on the last known policies for up to 5 minutes.
QA5	Adoption. A developer who has not seen the code (for example, a classmate) puts a new REST tool behind the gateway (import, risk level, policy) in under 10 minutes with no code changes.
SPRING BOOT ARCHITECTURE FOCUS
Spring AI MCP server and client starters to expose and consume tools; choosing Spring MVC with virtual threads or WebFlux for the streaming transport, and justifying the choice.
Spring Security: OAuth2 authorization and resource server, a custom AuthorizationManager for policy decisions, method security.
Guards and policies as ordered, pluggable Spring beans, so a new guard is a new bean rather than an edit (Chain of Responsibility, Strategy, Decorator).
Approval lifecycle as a state machine (State pattern or Spring Statemachine); audit through application events and @TransactionalEventListener.
Resilience4j circuit breakers and timeouts; Micrometer metrics and tracing.
Also expected where they fit: Proxy, Adapter, Command, Observer.
Out of scope: building the agents themselves (scripted MCP clients act as agents; a local model is optional), a full identity-provider product, multi-region deployment.
Course coverage: Lessons 2, 5–11, 13, 18, 21–22.
````

## Common requirements C1–C12

````text
Requirements common to all five projects
C1	Stack. Java 21 or 25 on Spring Boot 4.x, built with Maven or Gradle. The whole system starts with one command through Docker Compose on a laptop with 8 GB RAM. No paid cloud services and no paid API keys.
C2	Configuration. Profiles plus environment variables; typed @ConfigurationProperties with validation; no secrets in the repository.
C3	API quality. OpenAPI documentation (springdoc); Spring Security on every endpoint; Bean Validation on inputs; errors as RFC 9457 Problem Details; pagination on every list endpoint.
C4	Observability. Spring Boot Actuator health and readiness endpoints; Micrometer metrics and tracing; structured JSON logs carrying a trace ID across components.
C5	Tests. JUnit 5 unit and slice tests, integration tests with Testcontainers (database, broker, Redis as used), and every quality-attribute measure as a runnable script (k6, Gatling or JMeter for load). CI runs them all.
C6	Architecture rules as tests. ArchUnit or Spring Modulith verification enforces the module and layer boundaries the student chose.
C7	Architecture documentation. C4 context, container and component diagrams; at least 4 ADRs (context, options, decision, consequences, with measured evidence); a utility tree with at least 6 scenarios plus sensitivity and trade-off points (mini-ATAM).
C8	Pattern justification. A table giving, for each pattern used, the requirement it serves, the participating classes and the alternative rejected. Patterns not tied to a requirement earn no credit.
C9	Evidence report. Results for every quality attribute, with raw outputs and charts, including failed runs and what was changed in response.
C10	Checkpoint change request. Implemented after the instructor issues it, with a note listing the files touched in core code versus new components.
C11	Process evidence. Incremental git history (a single bulk commit is penalised) and an AI-usage log: tools used, key prompts, and at least three cases where the agent's output was wrong or incomplete, how that was found and how it was fixed.
C12	Live defence. 15 minutes: walk through one ADR and make a small instructor-requested change on the spot.
````
