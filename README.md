# CodeLens-AI
CodeLens AI is a repository-aware RAG (Retrieval-Augmented Generation) system. Upload a ZIP of a codebase and ask questions in plain English. Answers are generated only from the retrieved source code and come with file and line-number citations, so you can verify every claim.

💡 Why CodeLens?

A normal AI chatbot answers from its general training and can invent files or methods that do not exist in your project. CodeLens retrieves the actual code relevant to your question first, then asks the LLM to answer using only that code.

Normal chatbot :  Question ──────────────────────────► LLM ──► Answer (may be invented)

CodeLens       :  Question ─► Embed ─► Similar code chunks ─► LLM ─► Answer + sources

Example questions:

Where is JWT authentication implemented?
Explain the employee creation flow.
Which controller handles leave requests?

⚙️ How It Works
ZIP upload
   ↓
Scan files        skip node_modules/, .git/, target/, build/, dist/
   ↓
Classify files    Controller / Service / Repository / Entity / React component
   ↓
Chunk by method   brace-counting parser, one chunk per method (not fixed-size splits)
   ↓
Embed             Gemini text-embedding-004 → 768-dimension vector per chunk
   ↓
Store             PostgreSQL + pgvector (native vector column)
   ─────────────────────── at question time ───────────────────────
User question
   ↓
Embed the question (same model)
   ↓
pgvector cosine search (<=>)  →  top 6 most relevant chunks
   ↓
Constrained prompt: "Answer ONLY from this context. Cite files. Say if evidence is insufficient."
   ↓
Groq LLM  →  { answer, sources: [ { filePath, startLine, endLine } ] }

🛠 Tech Stack
Layer	Technology
Backend	Java 17, Spring Boot 3.x, Spring Data JPA, Spring WebFlux (WebClient only)
Database	PostgreSQL with the pgvector extension
Embeddings	Google Gemini text-embedding-004
Answer generation	Groq (llama-3.3-70b-versatile)
Frontend (in progress)	React, Vite, Tailwind CSS, highlight.js
Infrastructure	Docker (pgvector/pgvector:pg16), Maven

Why two AI providers? Groq offers chat completions but no embeddings endpoint, so Gemini creates embeddings and Groq generates the final answer.

🧠 Design Decisions
Decision	Reason
Chunk by method boundaries	A half-method is poor context. Brace counting finds the exact end of each method
Embeddings instead of keyword search	Questions rarely use the same words as the code. Embeddings match meaning
Constrained prompt	The model can only talk about code it was shown, which limits hallucination
Citations on every answer	Lets a developer click through and verify the claim
pgvector instead of a Java loop	Similarity runs inside the database and can use an HNSW index as data grows
Deterministic stats (no AI)	File counts and classification use simple pattern matching, so they cost nothing and are consistent

🗃 Data Model
repository     id, name, uploaded_at, status (UPLOADING | INDEXING | READY | FAILED)
code_file      id, repository_id, path, language, file_type, content, size_bytes
code_chunk     id, file_id, repository_id, content, start_line, end_line,
               class_name, method_name, embedding  -- vector(768)
chat_message   id, repository_id, role (USER | ASSISTANT), content, created_at

⚙️ Getting Started
Prerequisites
JDK 17+, Maven
Docker Desktop
A free Gemini API key and a free Groq API key
1. Clone
bash
git clone https://github.com/<your-username>/codelens-ai.git
cd codelens-ai
2. Start PostgreSQL with pgvector
bash
docker compose up -d
docker ps          # confirm the pgvector/pgvector:pg16 container is running

The plain postgres image does not include pgvector, so use the pgvector/pgvector image.

3. Enable the vector extension

src/main/resources/schema.sql:

sql
CREATE EXTENSION IF NOT EXISTS vector;

It must run before Hibernate creates tables, so do not set spring.jpa.defer-datasource-initialization=true.

Verify:

bash
docker exec -it <container_name> psql -U postgres -d codelens -c "SELECT extname FROM pg_extension;"
4. Configure
properties
spring.datasource.url=jdbc:postgresql://localhost:5432/codelens
spring.datasource.username=postgres
spring.datasource.password=<your-password>
spring.jpa.hibernate.ddl-auto=update

gemini.api.key=${GEMINI_API_KEY}
groq.api.key=${GROQ_API_KEY}
bash
export GEMINI_API_KEY=your_key
export GROQ_API_KEY=your_key

Never commit API keys. Keep them in environment variables.

5. Run
bash
mvn spring-boot:run
📡 API Reference
Method	Endpoint	Description
POST	/api/repositories/upload	Upload a ZIP, starts ingestion and indexing
GET	/api/repositories/{id}/stats	File counts, language split, controllers / services / entities
GET	/api/repositories/{id}/files	File list
GET	/api/files/{id}	Raw file content
POST	/api/repositories/{id}/chat	Ask a question, get an answer with sources

🧪 Example

Request

http
POST /api/repositories/1/chat
Content-Type: application/json

{ "question": "How does employee creation work?" }

Response

json
{
  "answer": "Employee creation starts in EmployeeController (POST /api/employees), which calls EmployeeService.createEmployee(). The service validates the request and saves the entity through EmployeeRepository.",
  "sources": [
    { "filePath": "controller/EmployeeController.java", "startLine": 39, "endLine": 51 },
    { "filePath": "service/EmployeeService.java", "startLine": 28, "endLine": 49 }
  ]
}

🗺 Roadmap

Core pipeline

 ZIP upload and file filtering
 File classification and stats
 Method-boundary chunking
 Gemini embeddings
 pgvector similarity search
 Constrained RAG answers with citations

In progress

 React frontend (upload, dashboard, chat, code viewer)
 Unit tests (chunking, similarity, classification)
 Dockerize backend and frontend

Planned

 GitHub repository import (OAuth)
 Incremental re-indexing using file hashes
 HNSW index on the embedding column
 Hybrid search (semantic + keyword)
 User accounts with per-user repository isolation
 "Trace this API" and "Find usages" using static analysis plus RAG

Update the checkboxes to match exactly what you have finished before publishing.

⚠️ Limitations
Single-user local setup; no authentication yet
Brace-counting chunker is built for Java and JavaScript-style syntax, not every language
Answer quality depends on retrieval quality; the model is told to say when evidence is insufficient
Full re-index on every upload
📁 Project Structure
com.codelens
 ├── config/        datasource, Gemini and Groq client config
 ├── model/         Repository, CodeFile, CodeChunk, ChatMessage
 ├── dto/           request/response objects, SourceRef
 ├── ingestion/     ZipExtractorService, FileClassifierService, ChunkingService
 ├── embedding/     GeminiEmbeddingClient, SimilaritySearchService
 ├── rag/           PromptBuilder, GroqChatClient, RagService
 ├── controller/    RepositoryController, FileController, ChatController
 └── exception/     GlobalExceptionHandler
