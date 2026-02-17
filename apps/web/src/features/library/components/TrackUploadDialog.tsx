import { useState, useCallback, useRef } from 'react';
import { Upload, X, CheckCircle2, AlertCircle, Link } from 'lucide-react';
import { getOrCreateDeviceId, DEFAULT_CLIENT_NAME, DEFAULT_DEVICE_NAME } from '@yay-tsa/core';

interface UploadResult {
  success: boolean;
  message: string;
  trackName?: string;
}

type UploadMode = 'file' | 'url';

export function TrackUploadDialog({
  isOpen,
  onClose,
  onUploadSuccess,
}: {
  isOpen: boolean;
  onClose: () => void;
  onUploadSuccess?: () => void;
}) {
  const [mode, setMode] = useState<UploadMode>('file');
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState('');
  const [isDragging, setIsDragging] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [result, setResult] = useState<UploadResult | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
  }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);

    const droppedFile = e.dataTransfer.files[0];
    if (droppedFile && isAudioFile(droppedFile)) {
      setFile(droppedFile);
      setResult(null);
    }
  }, []);

  const handleFileSelect = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFile = e.target.files?.[0];
    if (selectedFile && isAudioFile(selectedFile)) {
      setFile(selectedFile);
      setResult(null);
    }
  }, []);

  const isAudioFile = (file: File): boolean => {
    const supportedFormats = ['audio/mpeg', 'audio/flac', 'audio/x-flac', 'audio/mp4', 'audio/aac', 'audio/ogg', 'audio/opus', 'audio/wav', 'audio/x-wav'];
    const supportedExtensions = ['.mp3', '.flac', '.m4a', '.aac', '.ogg', '.opus', '.wav', '.wma'];

    return supportedFormats.includes(file.type) ||
           supportedExtensions.some(ext => file.name.toLowerCase().endsWith(ext));
  };

  const handleUploadFile = async () => {
    if (!file) return;

    setIsUploading(true);
    setUploadProgress(0);

    const formData = new FormData();
    formData.append('file', file);

    try {
      const xhr = new XMLHttpRequest();

      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) {
          const progress = Math.round((event.loaded / event.total) * 100);
          setUploadProgress(progress);
        }
      };

      xhr.onload = () => {
        if (xhr.status === 201) {
          setResult({
            success: true,
            message: 'Track uploaded successfully!',
            trackName: file.name,
          });
          setFile(null);
          onUploadSuccess?.();
        } else if (xhr.status === 409) {
          const response = xhr.responseText;
          setResult({
            success: false,
            message: response || 'Duplicate track already exists',
          });
        } else {
          const response = xhr.responseText;
          setResult({
            success: false,
            message: response || `Upload failed (${xhr.status})`,
          });
        }
        setIsUploading(false);
      };

      xhr.onerror = () => {
        setResult({
          success: false,
          message: 'Network error during upload',
        });
        setIsUploading(false);
      };

      xhr.open('POST', '/api/tracks/upload');

      // Get auth token from storage
      const token = sessionStorage.getItem('yaytsa_session') || localStorage.getItem('yaytsa_session_persistent');
      const deviceId = getOrCreateDeviceId();

      if (token) {
        // Use Emby auth format
        xhr.setRequestHeader('X-Emby-Authorization',
          `MediaBrowser Token="${token}", DeviceId="${deviceId}", Device="${DEFAULT_DEVICE_NAME}", Client="${DEFAULT_CLIENT_NAME}", Version="1.0.0"`
        );
      }

      xhr.send(formData);
    } catch (error) {
      setResult({
        success: false,
        message: error instanceof Error ? error.message : 'Upload failed',
      });
      setIsUploading(false);
    }
  };

  const handleUploadUrl = async () => {
    if (!url.trim()) return;

    setIsUploading(true);
    setUploadProgress(0);

    try {
      const token = sessionStorage.getItem('yaytsa_session') || localStorage.getItem('yaytsa_session_persistent');
      const deviceId = getOrCreateDeviceId();

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      };

      if (token) {
        headers['X-Emby-Authorization'] = `MediaBrowser Token="${token}", DeviceId="${deviceId}", Device="${DEFAULT_DEVICE_NAME}", Client="${DEFAULT_CLIENT_NAME}", Version="1.0.0"`;
      }

      const response = await fetch('/api/tracks/upload-url', {
        method: 'POST',
        headers,
        body: JSON.stringify({ url: url.trim() }),
      });

      if (response.ok) {
        setResult({
          success: true,
          message: 'Track downloaded and uploaded successfully!',
        });
        setUrl('');
        onUploadSuccess?.();
      } else if (response.status === 409) {
        const text = await response.text();
        setResult({
          success: false,
          message: text || 'Duplicate track already exists',
        });
      } else {
        const text = await response.text();
        setResult({
          success: false,
          message: text || `Upload failed (${response.status})`,
        });
      }
    } catch (error) {
      setResult({
        success: false,
        message: error instanceof Error ? error.message : 'Upload failed',
      });
    } finally {
      setIsUploading(false);
    }
  };

  const handleUpload = () => {
    if (mode === 'file') {
      handleUploadFile();
    } else {
      handleUploadUrl();
    }
  };

  const handleReset = () => {
    setFile(null);
    setUrl('');
    setResult(null);
    setUploadProgress(0);
  };

  const handleClose = () => {
    if (!isUploading) {
      handleReset();
      onClose();
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="bg-surface w-full max-w-md rounded-lg shadow-xl">
        <div className="border-b border-white/10 p-4 flex items-center justify-between">
          <h2 className="text-xl font-semibold">Upload Track</h2>
          <button
            onClick={handleClose}
            disabled={isUploading}
            className="text-text-secondary hover:text-text-primary disabled:opacity-50"
          >
            <X size={24} />
          </button>
        </div>

        <div className="p-6 space-y-4">
          {/* Mode tabs */}
          <div className="flex gap-2 border-b border-white/10">
            <button
              onClick={() => setMode('file')}
              className={`flex items-center gap-2 px-4 py-2 font-medium transition-colors ${
                mode === 'file'
                  ? 'text-primary border-b-2 border-primary'
                  : 'text-text-secondary hover:text-text-primary'
              }`}
            >
              <Upload size={18} />
              File Upload
            </button>
            <button
              onClick={() => setMode('url')}
              className={`flex items-center gap-2 px-4 py-2 font-medium transition-colors ${
                mode === 'url'
                  ? 'text-primary border-b-2 border-primary'
                  : 'text-text-secondary hover:text-text-primary'
              }`}
            >
              <Link size={18} />
              URL Download
            </button>
          </div>

          {/* File upload mode */}
          {mode === 'file' && !file && !isUploading && !result && (
            <div
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
              onClick={() => fileInputRef.current?.click()}
              className={`border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-colors ${
                isDragging
                  ? 'border-primary bg-primary/10'
                  : 'border-white/20 hover:border-primary/50 hover:bg-white/5'
              }`}
            >
              <Upload size={48} className="mx-auto mb-4 text-text-secondary" />
              <p className="text-text-primary font-medium mb-2">
                Drop audio file here or click to browse
              </p>
              <p className="text-text-secondary text-sm">
                Supported: MP3, FLAC, M4A, OGG, WAV (max 100MB)
              </p>
              <input
                ref={fileInputRef}
                type="file"
                accept="audio/*,.mp3,.flac,.m4a,.aac,.ogg,.opus,.wav,.wma"
                onChange={handleFileSelect}
                className="hidden"
              />
            </div>
          )}

          {/* URL upload mode */}
          {mode === 'url' && !isUploading && !result && (
            <div className="space-y-4">
              <div>
                <label className="block text-text-primary font-medium mb-2">
                  Enter URL
                </label>
                <input
                  type="url"
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  placeholder="https://youtube.com/watch?v=... or other audio URL"
                  className="w-full px-4 py-3 rounded-lg bg-white/5 border border-white/20 text-text-primary placeholder:text-text-secondary focus:outline-none focus:border-primary transition-colors"
                />
              </div>
              <p className="text-text-secondary text-sm">
                Supports YouTube, SoundCloud, and other audio sources
              </p>
              {url.trim() && (
                <div className="flex gap-3">
                  <button
                    onClick={() => setUrl('')}
                    className="flex-1 px-4 py-2 rounded-lg border border-white/20 text-text-primary hover:bg-white/5 transition-colors"
                  >
                    Clear
                  </button>
                  <button
                    onClick={handleUpload}
                    className="flex-1 px-4 py-2 rounded-lg bg-primary text-white hover:bg-primary/90 transition-colors font-medium"
                  >
                    Download & Upload
                  </button>
                </div>
              )}
            </div>
          )}

          {/* File selected or upload in progress/completed */}
          {((mode === 'file' && file) || isUploading || result) && (
            <div className="space-y-4">
              {mode === 'file' && file && (
                <div className="bg-white/5 rounded-lg p-4">
                  <p className="text-text-primary font-medium mb-1 truncate">{file.name}</p>
                  <p className="text-text-secondary text-sm">
                    {(file.size / 1024 / 1024).toFixed(2)} MB
                  </p>
                </div>
              )}

              {mode === 'url' && url && !isUploading && !result && (
                <div className="bg-white/5 rounded-lg p-4">
                  <p className="text-text-primary font-medium mb-1 truncate">{url}</p>
                </div>
              )}

              {isUploading && (
                <div className="space-y-2">
                  <div className="flex justify-between text-sm">
                    <span className="text-text-secondary">Uploading...</span>
                    <span className="text-text-primary font-medium">{uploadProgress}%</span>
                  </div>
                  <div className="bg-white/10 rounded-full h-2 overflow-hidden">
                    <div
                      className="bg-primary h-full transition-all duration-300"
                      style={{ width: `${uploadProgress}%` }}
                    />
                  </div>
                </div>
              )}

              {result && (
                <div
                  className={`flex items-start gap-3 rounded-lg p-4 ${
                    result.success ? 'bg-green-500/10 text-green-400' : 'bg-red-500/10 text-red-400'
                  }`}
                >
                  {result.success ? (
                    <CheckCircle2 size={20} className="flex-shrink-0 mt-0.5" />
                  ) : (
                    <AlertCircle size={20} className="flex-shrink-0 mt-0.5" />
                  )}
                  <div className="flex-1">
                    <p className="font-medium">{result.message}</p>
                  </div>
                </div>
              )}

              <div className="flex gap-3">
                {!isUploading && !result && (
                  <>
                    <button
                      onClick={handleReset}
                      className="flex-1 px-4 py-2 rounded-lg border border-white/20 text-text-primary hover:bg-white/5 transition-colors"
                    >
                      Choose Different File
                    </button>
                    <button
                      onClick={handleUpload}
                      className="flex-1 px-4 py-2 rounded-lg bg-primary text-white hover:bg-primary/90 transition-colors font-medium"
                    >
                      Upload
                    </button>
                  </>
                )}
                {result && (
                  <button
                    onClick={result.success ? handleClose : handleReset}
                    className="flex-1 px-4 py-2 rounded-lg bg-primary text-white hover:bg-primary/90 transition-colors font-medium"
                  >
                    {result.success ? 'Done' : 'Try Again'}
                  </button>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
