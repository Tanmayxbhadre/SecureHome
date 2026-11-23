// SecureHome Download Script
document.addEventListener("DOMContentLoaded", () => {
  const qrContainer = document.getElementById("apk-qr");
  const qrTargetText = document.getElementById("qr-target-url");
  const ipInput = document.getElementById("ip-override");
  const updateBtn = document.getElementById("btn-update-qr");
  const targetSelect = document.getElementById("qr-mode-select");

  // Default detected LAN IP of host machine for local development
  const DEFAULT_LAN_IP = "10.225.210.183";
  const isLocalhost = window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1";

  if (ipInput && isLocalhost) {
    ipInput.value = DEFAULT_LAN_IP;
  }

  function getTargetUrl() {
    const isApkMode = targetSelect ? targetSelect.value === "apk" : true;
    const targetFile = isApkMode ? "securehome.apk" : "download.html";

    if (isLocalhost) {
      const customIp = (ipInput && ipInput.value.trim()) || DEFAULT_LAN_IP;
      const port = window.location.port ? `:${window.location.port}` : ":8787";
      return `http://${customIp}${port}/${targetFile}`;
    }

    return new URL(targetFile, window.location.href).href;
  }

  function updateQr() {
    if (!qrContainer) return;
    const url = getTargetUrl();

    if (qrTargetText) {
      qrTargetText.textContent = url;
    }

    if (typeof qrcode !== "function") {
      qrContainer.textContent = "QR generator unavailable. Visit: " + url;
      return;
    }

    try {
      const q = qrcode(0, "M");
      q.addData(url);
      q.make();
      qrContainer.innerHTML = q.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
    } catch (e) {
      console.warn("QR render failed", e);
      qrContainer.textContent = url;
    }
  }

  if (updateBtn) {
    updateBtn.addEventListener("click", updateQr);
  }

  if (ipInput) {
    ipInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") updateQr();
    });
  }

  if (targetSelect) {
    targetSelect.addEventListener("change", updateQr);
  }

  // Initial render
  updateQr();

  // Handle copy CLI commands
  setupCopyBtn("btn-copy-adb", "adb install -r securehome.apk");
  setupCopyBtn("btn-copy-reverse", "adb reverse tcp:8787 tcp:8787");
});

function setupCopyBtn(btnId, textToCopy) {
  const btn = document.getElementById(btnId);
  if (!btn) return;

  btn.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(textToCopy);
      const original = btn.textContent;
      btn.textContent = "Copied!";
      setTimeout(() => {
        btn.textContent = original;
      }, 2000);
    } catch (err) {
      console.error("Clipboard copy failed", err);
    }
  });
}
