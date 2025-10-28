FROM node:20-alpine

# Install dependencies for testing
RUN apk add --no-cache \
    bash \
    git \
    curl

# Create app directory
WORKDIR /app

# Copy package files
COPY packages/core/package*.json ./packages/core/

# Install dependencies
WORKDIR /app/packages/core
RUN npm install

# Copy source code
COPY packages/core/ ./

# Run tests by default
CMD ["npm", "run", "test:integration"]
