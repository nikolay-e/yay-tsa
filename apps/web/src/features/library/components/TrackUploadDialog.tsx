import { useState, useCallback, useRef } from 'react';
import { Upload, X, CheckCircle2, AlertCircle, Music } from 'lucide-react';
import { useAuthStore } from '@/features/auth/stores/auth.store';

const SUPPORTED_FORMATS = [
  'audio/mpeg',
  'audio/flac',
  'audio/x-flac',
  'audio/mp4',
  'audio/aac',
  'audio/ogg',
  'audio/opus',
  'audio/wav',
  'audio/x-wav',
];

const SUPPORTED_EXTENSIONS = ['.mp3', '.flac', '.m4a', '.aac', '.ogg', '.opus', '.wav', '.wma'];

function isAudioFile(file: File): boolean {
  return (
    SUPPORTED_FORMATS.includes(file.type) ||
    SUPPORTED_EXTENSIONS.some(ext => file.name.toLowerCase().endsWith(ext))
  );
}

interface FileUploadStatus {
  file: File;
  status: 'pending' | 'uploading' | 'success' | 'duplicate' | 'error';
  progress: number;
  message?: string;
  albumComplete?: boolean;
}

export function TrackUploadDialog({
  isOpen,
  onClose,
  onUploadSuccess,
}: {
  isOpen: boolean;
  onClose: () => void;
  onUploadSuccess?: () => void;
}) {
  const [files, setFiles] = useState<FileUploadStatus[]>([]);
  const [isDragging, setIsDragging] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const client = useAuthStore(s => s.client);

  const addFiles = useCallback((newFiles: FileList | File[]) => {
    const audioFiles = Array.from(newFiles).filter(isAudioFile);
    if (audioFiles.length === 0) return;

    setFiles(prev => {
      const existingNames = new Set(prev.map(f => f.file.name));
      const unique = audioFiles.filter(f => !existingNames.has(f.name));
      return [...prev, ...unique.map(file => ({ file, status: 'pending' as const, progress: 0 }))];
    });
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
  }, []);

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setIsDragging(false);
      addFiles(e.dataTransfer.files);
    },
    [addFiles]
  );

  const handleFileSelect = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      if (e.target.files) {
        addFiles(e.target.files);
      }
      e.target.value = '';
    },
    [addFiles]
  );

  const removeFile = useCallback((index: number) => {
    setFiles(prev => prev.filter((_, i) => i !== index));
  }, []);

  const uploadSingleFile = (fileStatus: FileUploadStatus, index: number): Promise<boolean> => {
    return new Promise(resolve => {
      if (!client) {
        setFiles(prev =>
          prev.map((f, i) =>
            i === index ? { ...f, status: 'error', message: 'Not authenticated' } : f
          )
        );
        resolve(false);
        return;
      }

      const formData = new FormData();
      formData.append('file', fileStatus.file);

      const xhr = new XMLHttpRequest();

      xhr.upload.onprogress = event => {
        if (event.lengthComputable) {
          const progress = Math.round((event.loaded / event.total) * 100);
          setFiles(prev =>
            prev.map((f, i) => (i === index ? { ...f, progress, status: 'uploading' } : f))
          );
        }
      };

      xhr.onload = () => {
        if (xhr.status === 201) {
          let albumComplete: boolean | undefined;
          try {
            const data = JSON.parse(xhr.responseText) as { IsComplete?: boolean };
            albumComplete = data.IsComplete;
          } catch { /* ignore parse errors */ }
          setFiles(prev =>
            prev.map((f, i) =>
              i === index
                ? {
                    ...f,
                    status: 'success',
                    progress: 100,
                    message: albumComplete === false ? 'Waiting for more tracks' : 'Uploaded',
                    albumComplete,
                  }
                : f
            )
          );
          resolve(true);
        } else if (xhr.status === 409) {
          setFiles(prev =>
            prev.map((f, i) =>
              i === index
                ? {
                    ...f,
                    status: 'duplicate',
                    progress: 100,
                    message: xhr.responseText || 'Duplicate track',
                  }
                : f
            )
          );
          resolve(true);
        } else {
          setFiles(prev =>
            prev.map((f, i) =>
              i === index
                ? {
                    ...f,
                    status: 'error',
                    message: xhr.responseText || `Failed (${xhr.status})`,
                  }
                : f
            )
          );
          resolve(false);
        }
      };

      xhr.onerror = () => {
        setFiles(prev =>
          prev.map((f, i) =>
            i === index ? { ...f, status: 'error', message: 'Network error' } : f
          )
        );
        resolve(false);
      };

      xhr.open('POST', '/api/tracks/upload');
      xhr.setRequestHeader('X-Emby-Authorization', client.buildAuthHeader());
      xhr.send(formData);
    });
  };

  const handleUpload = async () => {
    const pendingFiles = files.filter(f => f.status === 'pending');
    if (pendingFiles.length === 0) return;

    setIsUploading(true);
    let anySuccess = false;

    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      if (!f || f.status !== 'pending') continue;
      const success = await uploadSingleFile(f, i);
      if (success) anySuccess = true;
    }

    setIsUploading(false);
    if (anySuccess) {
      onUploadSuccess?.();
    }
  };

  const handleReset = () => {
    setFiles([]);
  };

  const handleClose = () => {
    if (!isUploading) {
      handleReset();
      onClose();
    }
  };

  if (!isOpen) return null;

  const pendingCount = files.filter(f => f.status === 'pending').length;
  const successCount = files.filter(f => f.status === 'success').length;
  const duplicateCount = files.filter(f => f.status === 'duplicate').length;
  const errorCount = files.filter(f => f.status === 'error').length;
  const uploadingIndex = files.findIndex(f => f.status === 'uploading');
  const allDone = files.length > 0 && pendingCount === 0 && uploadingIndex === -1;
  const totalSize = files.reduce((acc, f) => acc + f.file.size, 0);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="bg-bg-secondary w-full max-w-lg rounded-lg shadow-xl">
        <div className="flex items-center justify-between border-b border-white/10 p-4">
          <h2 className="text-xl font-semibold">Upload Album</h2>
          <button
            onClick={handleClose}
            disabled={isUploading}
            className="text-text-secondary hover:text-text-primary disabled:opacity-50"
          >
            <X size={24} />
          </button>
        </div>

        <div className="space-y-4 p-6">
          {/* Drop zone — always visible when not uploading */}
          {!isUploading && !allDone && (
            <div
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
              onClick={() => fileInputRef.current?.click()}
              className={`cursor-pointer rounded-lg border-2 border-dashed p-6 text-center transition-colors ${
                isDragging
                  ? 'border-primary bg-primary/10'
                  : 'border-white/20 hover:border-primary/50 hover:bg-white/5'
              }`}
            >
              <Upload size={36} className="text-text-secondary mx-auto mb-3" />
              <p className="text-text-primary mb-1 font-medium">
                Drop audio files here or click to browse
              </p>
              <p className="text-text-secondary text-sm">
                MP3, FLAC, M4A, OGG, WAV — select multiple files
              </p>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept="audio/*,.mp3,.flac,.m4a,.aac,.ogg,.opus,.wav,.wma"
                onChange={handleFileSelect}
                className="hidden"
              />
            </div>
          )}

          {/* File list */}
          {files.length > 0 && (
            <div className="space-y-2">
              <div className="text-text-secondary flex items-center justify-between text-sm">
                <span>
                  {files.length} file{files.length !== 1 ? 's' : ''} ({(totalSize / 1024 / 1024).toFixed(1)} MB)
                </span>
                {isUploading && uploadingIndex >= 0 && (
                  <span className="text-text-primary font-medium">
                    {successCount + 1} of {files.length}
                  </span>
                )}
                {allDone && (
                  <span className="font-medium text-green-400">
                    {successCount} uploaded
                    {duplicateCount > 0 ? `, ${duplicateCount} skipped` : ''}
                    {errorCount > 0 ? `, ${errorCount} failed` : ''}
                  </span>
                )}
              </div>

              <div className="max-h-60 space-y-1 overflow-y-auto">
                {files.map((f, i) => (
                  <div
                    key={f.file.name}
                    className="flex items-center gap-2 rounded bg-white/5 px-3 py-2"
                  >
                    <Music size={14} className="text-text-tertiary shrink-0" />
                    <span className="text-text-primary min-w-0 flex-1 truncate text-sm">
                      {f.file.name}
                    </span>
                    {f.status === 'pending' && !isUploading && (
                      <button
                        onClick={e => {
                          e.stopPropagation();
                          removeFile(i);
                        }}
                        className="text-text-tertiary hover:text-text-primary shrink-0"
                      >
                        <X size={14} />
                      </button>
                    )}
                    {f.status === 'uploading' && (
                      <span className="text-text-secondary shrink-0 text-xs tabular-nums">
                        {f.progress}%
                      </span>
                    )}
                    {f.status === 'success' && (
                      <span className="flex shrink-0 items-center gap-1">
                        {f.albumComplete === false ? (
                          <span className="text-amber-400 text-xs" title="Album incomplete — upload more tracks">⏳</span>
                        ) : (
                          <CheckCircle2 size={14} className="text-green-400" />
                        )}
                      </span>
                    )}
                    {f.status === 'duplicate' && (
                      <span className="text-text-tertiary shrink-0 text-xs" title={f.message}>
                        skip
                      </span>
                    )}
                    {f.status === 'error' && (
                      <span className="flex shrink-0 items-center gap-1" title={f.message}>
                        <AlertCircle size={14} className="text-red-400" />
                      </span>
                    )}
                  </div>
                ))}
              </div>

              {/* Upload progress bar */}
              {isUploading && uploadingIndex >= 0 && (
                <div className="h-1.5 overflow-hidden rounded-full bg-white/10">
                  <div
                    className="bg-primary h-full transition-all duration-300"
                    style={{
                      width: `${((successCount + duplicateCount + (files[uploadingIndex]?.progress ?? 0) / 100) / files.length) * 100}%`,
                    }}
                  />
                </div>
              )}
            </div>
          )}

          {/* Actions */}
          <div className="flex gap-3">
            {!isUploading && !allDone && files.length > 0 && (
              <>
                <button
                  onClick={handleReset}
                  className="text-text-primary flex-1 rounded-lg border border-white/20 px-4 py-2 transition-colors hover:bg-white/5"
                >
                  Clear
                </button>
                <button
                  onClick={handleUpload}
                  disabled={pendingCount === 0}
                  className="bg-primary hover:bg-primary/90 flex-1 rounded-lg px-4 py-2 font-medium text-white transition-colors disabled:opacity-50"
                >
                  Upload {pendingCount} track{pendingCount !== 1 ? 's' : ''}
                </button>
              </>
            )}
            {allDone && (
              <button
                onClick={handleClose}
                className="bg-primary hover:bg-primary/90 flex-1 rounded-lg px-4 py-2 font-medium text-white transition-colors"
              >
                Done
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
