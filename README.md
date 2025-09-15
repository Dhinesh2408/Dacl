# Excel/CSV Cleaner

A full‑stack tool to upload CSV/XLSX, pick the columns you need, clean/normalize data, and download a cleaned file.

## Tech
- Frontend: React + TypeScript (Vite)
- Backend: Spring Boot (Java 17)
- Parsing: PapaParse, xlsx (frontend); Apache POI, OpenCSV (backend)

## Features
- Upload CSV/XLSX and auto‑detect headers
- Select columns via checkboxes or type headers manually
- Cleaning options: trim, merge spaces, text case, ISO dates
- Advanced: dedupe by keys, drop empty rows/cols, type normalization, email/URL validation
- Download as CSV or XLSX
- Upload/download progress indicator

## Prerequisites
- Node.js 20.19+ (or 22.12+)
- Java 17
- Optional: MySQL (only if you later enable persistence)

## Quick start
1) Backend
```
cd backend
mvnw.cmd spring-boot:run
```
- If MySQL is not running, DB auto‑config is disabled by default so the app starts fine.
- To enable DB later, remove `spring.autoconfigure.exclude` in `backend/src/main/resources/application.properties` and set your datasource.

2) Frontend
```
cd frontend
npm install
npm run dev
```
Open the URL Vite prints (likely http://localhost:5173).

## Configuration
- Dev proxy: `frontend/vite.config.ts` proxies `/api` to `http://localhost:8080`
- CORS: allowed origin set to `http://localhost:5173` in backend

## Usage
1. Drag and drop a CSV/XLSX or browse to upload
2. Search/choose headers or type them manually and click “Apply headers”
3. (Optional) Click “Show advanced options” to tweak cleaning/validation/output
4. Click “Clean & Download”

## Notes
- Large files: progress bar shows upload/download percentages
- Output filename adapts to the chosen format (csv/xlsx)

## Scripts
- Backend build: `cd backend && mvnw.cmd -DskipTests package`
- Frontend dev: `cd frontend && npm run dev`

## License
MIT
