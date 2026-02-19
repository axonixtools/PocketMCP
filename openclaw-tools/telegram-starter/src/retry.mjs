function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function withRetry(task, options = {}) {
  const {
    maxAttempts = 3,
    baseDelayMs = 250,
    maxDelayMs = 2000,
    shouldRetry = () => true,
    onRetry = () => {}
  } = options;

  let lastError;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      return { value: await task(attempt), attempts: attempt };
    } catch (error) {
      lastError = error;
      if (attempt >= maxAttempts || !shouldRetry(error)) {
        break;
      }
      const jitter = Math.floor(Math.random() * 40);
      const delay = Math.min(maxDelayMs, baseDelayMs * (2 ** (attempt - 1)) + jitter);
      await onRetry({ attempt, delay, error });
      await sleep(delay);
    }
  }
  throw lastError;
}
