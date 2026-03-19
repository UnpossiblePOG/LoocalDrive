const API_BASE = window.location.origin; // Dynamically use the host the page was served from

document.addEventListener('DOMContentLoaded', () => {
    fetchFiles();
    setupDragAndDrop();
    setupWebSocket();
});

// Setup WebSocket
let ws;
function setupWebSocket() {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${wsProtocol}//${window.location.host}/ws`);

    let pingInterval;

    ws.onopen = () => {
        // Keep the connection alive to prevent timeouts
        pingInterval = setInterval(() => {
            if (ws.readyState === WebSocket.OPEN) {
                ws.send('ping');
            }
        }, 30000); // 30 seconds
    };

    ws.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            if (data.type === 'connect' || data.type === 'upload' || data.type === 'delete') {
                showToast(data.message, 'info', 15000); // 15 seconds display
            }
            if (data.type === 'upload' || data.type === 'delete') {
                fetchFiles();
            }
        } catch (e) {
            // Ignore non-JSON messages (like pong)
        }
    };

    ws.onclose = () => {
        clearInterval(pingInterval);
        // Automatically try to reconnect
        setTimeout(setupWebSocket, 5000);
    };
}

// Setup Drag and Drop / File Selection
function setupDragAndDrop() {
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('fileInput');

    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, preventDefaults, false);
    });

    function preventDefaults(e) {
        e.preventDefault();
        e.stopPropagation();
    }

    ['dragenter', 'dragover'].forEach(eventName => {
        dropZone.addEventListener(eventName, () => dropZone.classList.add('dragover'), false);
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, () => dropZone.classList.remove('dragover'), false);
    });

    dropZone.addEventListener('drop', handleDrop, false);
    fileInput.addEventListener('change', handleFileSelect, false);
}

function handleDrop(e) {
    const dt = e.dataTransfer;
    const files = dt.files;
    if (files.length > 0) {
        uploadFile(files[0]);
    }
}

function handleFileSelect(e) {
    const files = e.target.files;
    if (files.length > 0) {
        uploadFile(files[0]);
    }
    // Reset input so the same file can be selected again
    e.target.value = '';
}

// Upload File
function uploadFile(file) {
    const progressContainer = document.getElementById('progressContainer');
    const progressBar = document.getElementById('progressBar');

    // Start reading file as Base64 to send over plain socket
    const reader = new FileReader();

    reader.onloadstart = () => {
        progressContainer.style.display = 'block';
        progressBar.style.width = '10%';
    };

    reader.onprogress = (e) => {
        if (e.lengthComputable) {
            const percentLoaded = Math.round((e.loaded / e.total) * 50); // Reading is 50%
            progressBar.style.width = percentLoaded + '%';
        }
    };

    reader.onload = async (e) => {
        try {
            const base64Data = e.target.result;

            progressBar.style.width = '70%'; // Reading done, sending...

            const response = await fetch(`${API_BASE}/upload`, {
                method: 'POST',
                headers: {
                    'File-Name': file.name,
                    'Content-Type': 'text/plain'
                },
                body: base64Data
            });

            progressBar.style.width = '100%';

            if (response.ok) {
                showToast(`Successfully uploaded ${file.name}`, 'success');
                fetchFiles(); // Refresh list
            } else {
                const err = await response.text();
                showToast(`Upload failed: ${err}`, 'error');
            }
        } catch (error) {
            showToast(`Network error: ${error.message}`, 'error');
        } finally {
            setTimeout(() => {
                progressContainer.style.display = 'none';
                progressBar.style.width = '0%';
            }, 1000);
        }
    };

    reader.onerror = () => {
        showToast("Error reading file", 'error');
        progressContainer.style.display = 'none';
    };

    reader.readAsDataURL(file); // This gives us prefix + base64 which we strip in Java
}

// Fetch Files
async function fetchFiles() {
    const fileListEl = document.getElementById('fileList');
    fileListEl.innerHTML = '<div class="empty-state">Loading...</div>';

    try {
        const response = await fetch(`${API_BASE}/list`);
        if (!response.ok) throw new Error("Failed to fetch files");

        const files = await response.json();

        if (!Array.isArray(files) || files.length === 0) {
            fileListEl.innerHTML = '<div class="empty-state">No files found. Upload one to get started!</div>';
            return;
        }

        fileListEl.innerHTML = ''; // clear

        files.forEach((file, index) => {
            // Create element programmatically
            const div = document.createElement('div');
            div.className = 'file-item';
            div.style.animationDelay = `${index * 0.05}s`;

            div.innerHTML = `
                <div class="file-info">
                    <div class="file-icon">📄</div>
                    <div class="file-name">${escapeHTML(file)}</div>
                </div>
                <div style="display: flex; gap: 0.5rem;">
                    <button class="download-btn" onclick="downloadFile('${escapeHTML(file)}')">
                        Download
                    </button>
                    <button class="delete-btn" onclick="deleteFile('${escapeHTML(file)}')">
                        Delete
                    </button>
                </div>
            `;
            fileListEl.appendChild(div);
        });

    } catch (error) {
        fileListEl.innerHTML = `<div class="empty-state" style="color:var(--danger)">Error: ${error.message}</div>`;
        showToast("Failed to load list", 'error');
    }
}

// Delete File
async function deleteFile(fileName) {
    if (!confirm(`Are you sure you want to delete '${fileName}'?`)) {
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/delete`, {
            method: 'POST',
            headers: {
                'Content-Type': 'text/plain'
            },
            body: fileName
        });

        if (response.ok) {
            showToast(`Deleted ${fileName}`, 'success');
            fetchFiles(); // refresh
        } else {
            const err = await response.text();
            showToast(`Delete failed: ${err}`, 'error');
        }
    } catch (error) {
        showToast(`Network error: ${error.message}`, 'error');
    }
}

// Download File
function downloadFile(fileName) {
    const a = document.createElement('a');
    a.href = `${API_BASE}/download?file=${encodeURIComponent(fileName)}`;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
}

// Utils
function escapeHTML(str) {
    return str.replace(/[&<>'"]/g,
        tag => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            "'": '&#39;',
            '"': '&quot;'
        }[tag] || tag)
    );
}

function showToast(message, type = 'info', duration = 3000) {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    const content = document.createElement('span');
    content.textContent = message;

    const closeBtn = document.createElement('button');
    closeBtn.className = 'toast-close-btn';
    closeBtn.innerHTML = '&times;';

    toast.appendChild(content);
    toast.appendChild(closeBtn);

    container.appendChild(toast);

    let isRemoving = false;

    const removeToast = () => {
        if (isRemoving) return;
        isRemoving = true;
        toast.style.animation = 'toastFadeOut 0.3s ease forwards';
        setTimeout(() => {
            if (container.contains(toast)) {
                container.removeChild(toast);
            }
        }, 300);
    };

    closeBtn.addEventListener('click', removeToast);

    // Remove after specified duration
    setTimeout(removeToast, duration);
}
