import React, { useState, useCallback, useRef, useEffect } from 'react';
import { Upload, X, CheckCircle2, AlertCircle, Music } from 'lucide-react';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { Modal } from '@/shared/ui/Modal';

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

const MAX_FILE_SIZE_BYTES = 500 * 1024 * 1024; // 500 MB

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
  const activeXhrRef = useRef<XMLHttpRequest | null>(null);
  const client = useAuthStore(s => s.client);

  const handleReset = useCallback(() => {
    setFiles([]);
  }, []);

  const handleClose = useCallback(() => {
    if (!isUploading) {
      handleReset();
      onClose();
    }
  }, [isUploading, handleReset, onClose]);

  useEffect(() => {
    return () => {
      activeXhrRef.current?.abort();
    };
  }, []);

  const addFiles = useCallback((newFiles: FileList | File[]) => {
    const audioFiles = Array.from(newFiles).filter(isAudioFile);
    if (audioFiles.length === 0) return;

    const oversized = audioFiles.filter(f => f.size > MAX_FILE_SIZE_BYTES);
    const validFiles = audioFiles.filter(f => f.size <= MAX_FILE_SIZE_BYTES);

    if (oversized.length > 0) {
      setFiles(prev => [
        ...prev,
        ...oversized.map(file => ({
          file,
          status: 'error' as const,
          progress: 0,
          message: `File too large (max 500 MB)`,
        })),
      ]);
    }

    if (validFiles.length === 0) return;

    setFiles(prev => {
      const fileKey = (f: File) => `${f.name}_${f.size}_${f.lastModified}`;
      const existingKeys = new Set(prev.map(f => fileKey(f.file)));
      const unique = validFiles.filter(f => !existingKeys.has(fileKey(f)));
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
          } catch {
            /* ignore parse errors */
          }
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

      activeXhrRef.current = xhr;
      xhr.open('POST', `${client.getServerUrl()}/tracks/upload`);
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

  const pendingCount = files.filter(f => f.status === 'pending').length;
  const successCount = files.filter(f => f.status === 'success').length;
  const duplicateCount = files.filter(f => f.status === 'duplicate').length;
  const errorCount = files.filter(f => f.status === 'error').length;
  const uploadingIndex = files.findIndex(f => f.status === 'uploading');
  const allDone = files.length > 0 && pendingCount === 0 && uploadingIndex === -1;
  const totalSize = files.reduce((acc, f) => acc + f.file.size, 0);

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      ariaLabelledBy="upload-dialog-title"
      preventClose={isUploading}
      backdropClassName="flex items-center justify-center bg-black/50 p-4"
      className="bg-bg-secondary w-full max-w-lg rounded-lg shadow-xl"
    >
      <div className="border-border flex items-center justify-between border-b p-4">
        <h2 id="upload-dialog-title" className="text-xl font-semibold">
          Upload Album
        </h2>
        <button
          onClick={handleClose}
          disabled={isUploading}
          className="text-text-secondary hover:text-text-primary disabled:opacity-50"
        >
          <X size={24} />
        </button>
      </div>

      <div className="space-y-4 p-6">
        {!isUploading && !allDone && (
          <div
            role="button"
            tabIndex={0}
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
            onKeyDown={e => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                fileInputRef.current?.click();
              }
            }}
            className={`cursor-pointer rounded-lg border-2 border-dashed p-6 text-center transition-colors ${
              isDragging
                ? 'border-accent bg-accent/10'
                : 'hover:border-accent/50 border-border hover:bg-bg-hover'
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

        {files.length > 0 && (
          <div className="space-y-2">
            <div className="text-text-secondary flex items-center justify-between text-sm">
              <span>
                {files.length} file{files.length !== 1 ? 's' : ''} (
                {(totalSize / 1024 / 1024).toFixed(1)} MB)
              </span>
              {isUploading && uploadingIndex >= 0 && (
                <span className="text-text-primary font-medium">
                  {successCount + 1} of {files.length}
                </span>
              )}
              {allDone && (
                <span className="text-success font-medium">
                  {successCount} uploaded
                  {duplicateCount > 0 ? `, ${duplicateCount} skipped` : ''}
                  {errorCount > 0 ? `, ${errorCount} failed` : ''}
                </span>
              )}
            </div>

            <div className="max-h-60 space-y-1 overflow-y-auto">
              {files.map((f, i) => (
                <div
                  key={`${f.file.name}_${f.file.size}_${f.file.lastModified}`}
                  className="bg-bg-tertiary flex items-center gap-2 rounded px-3 py-2"
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
                        <span title="Album incomplete — upload more tracks">
                          <AlertCircle size={14} className="text-warning" />
                        </span>
                      ) : (
                        <CheckCircle2 size={14} className="text-success" />
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
                      <AlertCircle size={14} className="text-error" />
                    </span>
                  )}
                </div>
              ))}
            </div>

            {isUploading && uploadingIndex >= 0 && (
              <div className="bg-bg-tertiary h-1.5 overflow-hidden rounded-full">
                <div
                  className="bg-accent h-full transition-all duration-300"
                  style={{
                    width: `${((successCount + duplicateCount + errorCount + (files[uploadingIndex]?.progress ?? 0) / 100) / files.length) * 100}%`,
                  }}
                />
              </div>
            )}
          </div>
        )}

        <div className="flex gap-3">
          {!isUploading && !allDone && files.length > 0 && (
            <>
              <button
                onClick={handleReset}
                className="text-text-primary border-border hover:bg-bg-hover flex-1 rounded-lg border px-4 py-2 transition-colors"
              >
                Clear
              </button>
              <button
                onClick={handleUpload}
                disabled={pendingCount === 0}
                className="bg-accent hover:bg-accent/90 flex-1 rounded-lg px-4 py-2 font-medium text-white transition-colors disabled:opacity-50"
              >
                Upload {pendingCount} track{pendingCount !== 1 ? 's' : ''}
              </button>
            </>
          )}
          {allDone && (
            <button
              onClick={handleClose}
              className="bg-accent hover:bg-accent/90 flex-1 rounded-lg px-4 py-2 font-medium text-white transition-colors"
            >
              Done
            </button>
          )}
        </div>
      </div>
    </Modal>
  );
}
