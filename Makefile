.PHONY: ci-deps ci-lint ci-test ci-perf ci-build ci-build-frontend ci-build-feature-extractor ci-build-audio-separator ci-build-v2 ci-apk

NODE_VERSION ?= 22

ci-deps:
	npm ci

ci-lint:
	pip install pre-commit
	npm ci
	pre-commit run --all-files

ci-test:
	npm ci
	npm run build:core && npm run build:platform
	cd apps/web && npx playwright install --with-deps chromium
	cd apps/web && npx playwright test --project=chromium-mocked
	cd yay-tsa-v2 && chmod +x gradlew && ./gradlew build --no-daemon --stacktrace

ci-perf:
	npm ci
	npm run build
	npm install -g @lhci/cli
	lhci autorun
	npx bundlewatch --config .bundlewatchrc.json
	node scripts/bundle-report.mjs --max-total-gzip=210

ci-build-frontend:
	docker buildx build \
		--platform linux/amd64 \
		--file apps/web/Dockerfile \
		--target production \
		--build-arg APP_ENVIRONMENT=production \
		.

ci-build-feature-extractor:
	docker buildx build \
		--platform linux/amd64 \
		--build-arg VARIANT=essentia \
		./services/audio-ml

ci-build-v2:
	docker buildx build \
		--platform linux/amd64 \
		--file yay-tsa-v2/Dockerfile \
		.

ci-build-audio-separator:
	docker buildx build \
		--platform linux/amd64 \
		--build-arg VARIANT=gpu \
		./services/audio-ml

ci-build: ci-build-frontend ci-build-feature-extractor ci-build-v2

ci-apk:
	echo "$$ANDROID_KEYSTORE_B64" | base64 -d > apps/android-twa/keystore.jks
	cd apps/android-twa && KEYSTORE_FILE=$$(pwd)/keystore.jks \
		KEYSTORE_PASSWORD=$$ANDROID_KEYSTORE_PASSWORD \
		KEY_ALIAS=$$ANDROID_KEY_ALIAS \
		KEY_PASSWORD=$$ANDROID_KEY_PASSWORD \
		./gradlew assembleRelease --no-daemon
