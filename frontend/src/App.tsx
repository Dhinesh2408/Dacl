import { ChangeEvent, DragEvent, useMemo, useState } from 'react';
import './App.css';
import Papa from 'papaparse';
import * as XLSX from 'xlsx';

type FileType = 'csv' | 'xlsx' | 'unknown';

function App() {
  const [file, setFile] = useState<File | null>(null);
  const [headers, setHeaders] = useState<string[]>([]);
  const [selected, setSelected] = useState<Record<string, boolean>>({});
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [filterQuery, setFilterQuery] = useState('');
  const [manualHeaders, setManualHeaders] = useState('');
  const [optTrim, setOptTrim] = useState(true);
  const [optCollapseSpaces, setOptCollapseSpaces] = useState(true);
  const [optCase, setOptCase] = useState<'none' | 'lower' | 'upper' | 'title'>('none');
  const [optDate, setOptDate] = useState<'none' | 'iso'>('none');
  const [dedupeKeys, setDedupeKeys] = useState('');
  const [dropEmptyRows, setDropEmptyRows] = useState(true);
  const [dropEmptyCols, setDropEmptyCols] = useState(true);
  const [normalizeTypes, setNormalizeTypes] = useState(false);
  const [validateEmail, setValidateEmail] = useState(false);
  const [removeInvalidEmails, setRemoveInvalidEmails] = useState(false);
  const [validateUrl, setValidateUrl] = useState(false);
  const [removeInvalidUrls, setRemoveInvalidUrls] = useState(false);
  const [outputFormat, setOutputFormat] = useState<'csv' | 'xlsx'>('csv');
  const [keepOrder, setKeepOrder] = useState(true);
  const [lastSelection, setLastSelection] = useState<Record<string, boolean> | null>(null);
  const [uploadProgress, setUploadProgress] = useState<number>(0);
  const [downloadProgress, setDownloadProgress] = useState<number>(0);
  const [showAdvanced, setShowAdvanced] = useState(false);

  const selectedColumns = useMemo(() => headers.filter(h => selected[h]), [headers, selected]);

  const detectType = (f: File): FileType => {
    const lower = f.name.toLowerCase();
    if (lower.endsWith('.csv')) return 'csv';
    if (lower.endsWith('.xlsx') || lower.endsWith('.xls')) return 'xlsx';
    if (f.type.includes('csv')) return 'csv';
    if (f.type.includes('sheet') || f.type.includes('excel')) return 'xlsx';
    return 'unknown';
  };

  const handleFile = async (f: File | null) => {
    setError(null);
    setFile(f);
    setHeaders([]);
    setSelected({});
    if (!f) return;
    const type = detectType(f);
    try {
      if (type === 'csv') {
        const text = await f.text();
        const parsed = Papa.parse<string[]>(text, { preview: 1 });
        const row = parsed.data[0] || [];
        const hdrs = row.map(v => String(v).trim()).filter(Boolean);
        setHeaders(hdrs);
        setSelected(Object.fromEntries(hdrs.map(h => [h, true])));
      } else if (type === 'xlsx') {
        const buffer = await f.arrayBuffer();
        const wb = XLSX.read(buffer, { type: 'array' });
        const sheetName = wb.SheetNames[0];
        const sheet = wb.Sheets[sheetName];
        const json = XLSX.utils.sheet_to_json<(string | number)[]>(sheet, { header: 1, raw: false, range: 0 });
        const row = (json[0] as unknown[] | undefined) || [];
        const hdrs = row.map(v => String(v ?? '').trim()).filter(Boolean);
        setHeaders(hdrs);
        setSelected(Object.fromEntries(hdrs.map(h => [h, true])));
      } else {
        setError('Unsupported file type. Please upload CSV or XLSX.');
      }
    } catch (err) {
      console.error(err);
      setError('Failed to read file.');
    }
  };

  const onFileChange = async (e: ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0] || null;
    await handleFile(f);
  };

  const toggleHeader = (h: string) => {
    setSelected(prev => ({ ...prev, [h]: !prev[h] }));
  };

  const selectAll = (value: boolean) => {
    setSelected(Object.fromEntries(headers.map(h => [h, value])));
  };

  const invertSelection = () => {
    setSelected(prev => Object.fromEntries(headers.map(h => [h, !prev[h]])));
  };

  const applyManualHeaders = () => {
    setLastSelection(selected);
    const tokens = manualHeaders
      .split(/[\,\n]/)
      .map(s => s.trim())
      .filter(Boolean);
    if (tokens.length === 0) return;
    const next: Record<string, boolean> = {};
    for (const h of headers) {
      next[h] = tokens.some(t => t.toLowerCase() === h.toLowerCase());
    }
    setSelected(next);
  };

  const onDownloadClean = async () => {
    if (!file || selectedColumns.length === 0) {
      setError('Select a file and at least one column.');
      return;
    }
    setIsLoading(true);
    setError(null);
    setUploadProgress(0);
    setDownloadProgress(0);
    try {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('columns', selectedColumns.join(','));
      formData.append('trim', String(optTrim));
      formData.append('collapseSpaces', String(optCollapseSpaces));
      formData.append('textCase', optCase);
      formData.append('dateFormat', optDate);
      formData.append('dedupeKeys', dedupeKeys);
      formData.append('dropEmptyRows', String(dropEmptyRows));
      formData.append('dropEmptyCols', String(dropEmptyCols));
      formData.append('normalizeTypes', String(normalizeTypes));
      formData.append('validateEmail', String(validateEmail));
      formData.append('removeInvalidEmails', String(removeInvalidEmails));
      formData.append('validateUrl', String(validateUrl));
      formData.append('removeInvalidUrls', String(removeInvalidUrls));
      formData.append('outputFormat', outputFormat);
      formData.append('keepOrder', String(keepOrder));

      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/clean');
        xhr.responseType = 'blob';
        xhr.upload.onprogress = (e) => {
          if (e.lengthComputable) setUploadProgress(Math.round((e.loaded / e.total) * 100));
        };
        xhr.onprogress = (e) => {
          if (e.lengthComputable) setDownloadProgress(Math.round((e.loaded / e.total) * 100));
        };
        xhr.onerror = () => reject(new Error('Network error'));
        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            const blob = xhr.response as Blob;
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            const ext = outputFormat === 'xlsx' ? '.xlsx' : '.csv';
            const base = file.name.replace(/\.(xlsx|xls|csv)$/i, '');
            a.download = `cleaned_${base}${ext}`;
            document.body.appendChild(a);
            a.click();
            a.remove();
            URL.revokeObjectURL(url);
            resolve();
          } else {
            const reader = new FileReader();
            reader.onload = () => reject(new Error(String(reader.result)));
            reader.readAsText(xhr.response);
          }
        };
        xhr.send(formData);
      });
    } catch (err: unknown) {
      console.error(err);
      setError(err instanceof Error ? err.message : 'Failed to download.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <>
      <header className="navbar">
        <div className="brand">Excel/CSV Cleaner</div>
      </header>
      <div className="container">
        <h1>Excel/CSV Cleaner</h1>
        <div className="subtitle">Upload a file, pick only the columns you need, and download a clean CSV.</div>
        <div
          className={`dropzone ${isDragging ? 'dragging' : ''} ${!file ? 'empty-state' : ''}`}
          onDragOver={(e: DragEvent<HTMLDivElement>) => { e.preventDefault(); setIsDragging(true); }}
          onDragLeave={() => setIsDragging(false)}
          onDrop={(e: DragEvent<HTMLDivElement>) => {
            e.preventDefault(); setIsDragging(false);
            const f = e.dataTransfer.files?.[0] || null;
            void handleFile(f);
          }}
        >
          <input id="file-input" type="file" accept=".csv,.xlsx,.xls,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel" onChange={onFileChange} />
          {file ? (
            <label htmlFor="file-input">
              <strong>{file.name}</strong>
              <span> — choose another file or drop to replace</span>
            </label>
          ) : (
            <div className="empty">
              <p><strong>Drag & drop</strong> a CSV/XLSX here, or <u>browse</u></p>
            </div>
          )}
        </div>
        {error && <div className="error">{error}</div>}
        {headers.length > 0 && (
          <div className="selector">
            <div className="selector-toolbar">
              <input
                className="search"
                type="text"
                placeholder="Search headers..."
                value={filterQuery}
                onChange={e => setFilterQuery(e.target.value)}
              />
              <div className="selector-actions">
                <span className="count">{selectedColumns.length} / {headers.length} selected</span>
                <button onClick={() => selectAll(true)}>Select all</button>
                <button onClick={() => selectAll(false)}>Clear all</button>
                <button onClick={invertSelection}>Invert</button>
              </div>
            </div>
            <div className="manual">
              <textarea
                placeholder="Type headers to keep (comma or newline separated), e.g. First Name, Email, Company Name, Website"
                value={manualHeaders}
                onChange={(e) => setManualHeaders(e.target.value)}
              />
              <button className="btn apply" onClick={applyManualHeaders}>Apply headers</button>
            </div>
            {/* Advanced options (collapsed by default) */}
            <div className="advanced">
              <button type="button" className="advanced-toggle" onClick={() => setShowAdvanced(v => !v)}>
                <span>{showAdvanced ? 'Hide advanced options' : 'Show advanced options'}</span>
              </button>
              {showAdvanced && (
                <div className="clean-options">
                  <div className="opt">
                    <label>
                      <input type="checkbox" checked={optTrim} onChange={e => setOptTrim(e.target.checked)} />
                      <span>Trim spaces</span>
                    </label>
                  </div>
                  <div className="opt">
                    <label>
                      <input type="checkbox" checked={optCollapseSpaces} onChange={e => setOptCollapseSpaces(e.target.checked)} />
                      <span>Merge multiple spaces</span>
                    </label>
                  </div>
                  <div className="opt stack">
                    <label>Text case</label>
                    <select value={optCase} onChange={e => setOptCase(e.target.value as any)}>
                      <option value="none">No change</option>
                      <option value="lower">lowercase</option>
                      <option value="upper">UPPERCASE</option>
                      <option value="title">Title Case</option>
                    </select>
                  </div>
                  <div className="opt stack">
                    <label>Date format</label>
                    <select value={optDate} onChange={e => setOptDate(e.target.value as any)}>
                      <option value="none">No change</option>
                      <option value="iso">ISO (YYYY-MM-DD)</option>
                    </select>
                  </div>
                  <div className="opt stack">
                    <label>Dedupe by</label>
                    <input
                      type="text"
                      placeholder="Email, Company"
                      value={dedupeKeys}
                      onChange={e => setDedupeKeys(e.target.value)}
                    />
                  </div>
                  <div className="opt">
                    <label>
                      <input type="checkbox" checked={dropEmptyRows} onChange={e => setDropEmptyRows(e.target.checked)} />
                      <span>Drop empty rows</span>
                    </label>
                  </div>
                  <div className="opt">
                    <label>
                      <input type="checkbox" checked={dropEmptyCols} onChange={e => setDropEmptyCols(e.target.checked)} />
                      <span>Drop empty columns</span>
                    </label>
                  </div>
                  <div className="opt">
                    <label>
                      <input type="checkbox" checked={normalizeTypes} onChange={e => setNormalizeTypes(e.target.checked)} />
                      <span>Normalize types</span>
                    </label>
                  </div>
                  <div className="opt">
                    <label>
                      <input type="checkbox" checked={validateEmail} onChange={e => setValidateEmail(e.target.checked)} />
                      <span>Validate emails</span>
                    </label>
                    {validateEmail && (
                      <label>
                        <input type="checkbox" checked={removeInvalidEmails} onChange={e => setRemoveInvalidEmails(e.target.checked)} />
                        <span>Remove invalid</span>
                      </label>
                    )}
                  </div>
                  <div className="opt">
                    <label>
                      <input type="checkbox" checked={validateUrl} onChange={e => setValidateUrl(e.target.checked)} />
                      <span>Validate URLs</span>
                    </label>
                    {validateUrl && (
                      <label>
                        <input type="checkbox" checked={removeInvalidUrls} onChange={e => setRemoveInvalidUrls(e.target.checked)} />
                        <span>Remove invalid</span>
                      </label>
                    )}
                  </div>
                  <div className="opt stack">
                    <label>Download as</label>
                    <select value={outputFormat} onChange={e => setOutputFormat(e.target.value as any)}>
                      <option value="csv">CSV</option>
                      <option value="xlsx">XLSX</option>
                    </select>
                  </div>
                  <div className="opt">
                    <label>
                      <input type="checkbox" checked={keepOrder} onChange={e => setKeepOrder(e.target.checked)} />
                      <span>Keep original order</span>
                    </label>
                  </div>
                  {lastSelection && (
                    <div className="opt">
                      <button className="btn secondary" onClick={() => { if (lastSelection) setSelected(lastSelection); setLastSelection(null); }}>Undo last apply</button>
                    </div>
                  )}
                </div>
              )}
            </div>
            <div className="headers-grid">
              {headers.filter(h => h.toLowerCase().includes(filterQuery.toLowerCase())).map(h => (
                <label key={h} className={`header-item ${selected[h] ? 'selected' : ''}`}>
                  <input type="checkbox" checked={!!selected[h]} onChange={() => toggleHeader(h)} />
                  <span>{h}</span>
                </label>
              ))}
            </div>
          </div>
        )}
        {(isLoading || uploadProgress > 0 || downloadProgress > 0) && (
          <div className="progress">
            <div className="bar">
              <div className="fill" style={{ width: `${Math.max(uploadProgress, downloadProgress)}%` }} />
            </div>
            <div className="labels">
              <span>Upload: {uploadProgress}%</span>
              <span>Download: {downloadProgress}%</span>
            </div>
      </div>
        )}
        <div className="actions">
          <button className="btn" disabled={!file || selectedColumns.length === 0 || isLoading} onClick={onDownloadClean}>
            {isLoading ? 'Processing…' : (outputFormat === 'xlsx' ? 'Clean & Download XLSX' : 'Clean & Download CSV')}
        </button>
          <button className="btn secondary" onClick={() => { setFile(null); setHeaders([]); setSelected({}); setError(null); }}>Reset</button>
        </div>
        <footer>
          <small>Upload CSV or Excel, choose columns, download cleaned CSV.</small>
        </footer>
      </div>
    </>
  );
}

export default App;
