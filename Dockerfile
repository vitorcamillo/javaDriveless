FROM maven:3.9.9-eclipse-temurin-21

WORKDIR /app

# Google Linux Packages Signing Authority, primária.
ARG GOOGLE_LINUX_PKG_SIGNER_FPR=EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg wget libnss3-tools openssl \
    && install -d -m 0755 /etc/apt/keyrings \
    && wget -qO /tmp/google-linux.pub https://dl.google.com/linux/linux_signing_key.pub \
    && test "$(gpg --show-keys --with-colons /tmp/google-linux.pub | grep -A1 '^pub:' | grep '^fpr:')" = "fpr:::::::::${GOOGLE_LINUX_PKG_SIGNER_FPR}:" \
    && gpg --dearmor -o /etc/apt/keyrings/google-linux.gpg /tmp/google-linux.pub \
    && rm -f /tmp/google-linux.pub \
    && chmod a+r /etc/apt/keyrings/google-linux.gpg \
    && printf 'deb [arch=amd64 signed-by=/etc/apt/keyrings/google-linux.gpg] http://dl.google.com/linux/chrome/deb/ stable main\n' > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends google-chrome-stable \
        xvfb x11-utils util-linux fontconfig locales \
        fonts-liberation fonts-dejavu fonts-freefont-ttf \
        fonts-noto-core fonts-noto-cjk fonts-noto-color-emoji \
        fonts-sil-abyssinica fonts-sil-padauk fonts-lohit-taml \
    && sed -i '/pt_BR.UTF-8/s/^# //; /en_US.UTF-8/s/^# //' /etc/locale.gen \
    && locale-gen pt_BR.UTF-8 en_US.UTF-8 \
    && install -d -o root -g root -m 1777 /tmp/.X11-unix \
    && install -d -o root -g root -m 0755 /etc/opt/chrome/policies/managed \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY scripts/run-visible-xvfb.sh /usr/local/bin/java-driverless-visible-xvfb
COPY scripts/trust-controlled-doh-certificate.sh /usr/local/bin/java-driverless-trust-controlled-doh
RUN bash -n /usr/local/bin/java-driverless-visible-xvfb \
    && bash -n /usr/local/bin/java-driverless-trust-controlled-doh \
    && chmod 0755 /usr/local/bin/java-driverless-visible-xvfb \
        /usr/local/bin/java-driverless-trust-controlled-doh

COPY src ./src
COPY docs ./docs
COPY README.md LICENSE ./

RUN mvn -q -DskipTests package dependency:copy-dependencies \
        -DincludeScope=runtime \
        -DoutputDirectory=target/dependency \
    && printf '%s\n' 'target/dependency/*' > target/runtime.classpath \
    && groupadd --system --gid 10001 javadriveless \
    && useradd --system --uid 10001 --gid 10001 --create-home \
        --home-dir /home/javadriveless --shell /usr/sbin/nologin javadriveless \
    && install -d -o javadriveless -g javadriveless -m 0700 /home/javadriveless/.m2 \
    && chown -R javadriveless:javadriveless /app

ENV HOME=/home/javadriveless \
    MAVEN_CONFIG=/home/javadriveless/.m2 \
    XDG_DATA_HOME=/tmp/java-driverless-data \
    XDG_CONFIG_HOME=/tmp/java-driverless-config \
    XDG_CACHE_HOME=/tmp/java-driverless-cache \
    JAVA_DRIVERLESS_HEADLESS=0 \
    JAVA_DRIVERLESS_PROFILE_PRESET=docker \
    JAVA_DRIVERLESS_BROWSER_BINARY=/usr/bin/google-chrome-stable \
    JAVA_DRIVERLESS_DOCKER_PROOF_OUTPUT=/tmp/java-driverless-docker-proof.json \
    JAVA_DRIVERLESS_STARTUP_TIMEOUT_SECONDS=60

# Imagem de execução pontual: o healthcheck valida a integridade dos componentes
# de runtime (JRE, binario do Chrome e classpath empacotado), nao um servico longo.
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD java -version >/dev/null 2>&1 \
        && test -x "${JAVA_DRIVERLESS_BROWSER_BINARY}" \
        && test -f target/runtime.classpath \
        || exit 1

USER javadriveless

ENTRYPOINT []

CMD ["bash", "-lc", "exec /usr/local/bin/java-driverless-visible-xvfb --screen-width 1365 --screen-height 900 --avail-width 1365 --avail-height 860 -- java --enable-preview -cp target/classes:$(cat target/runtime.classpath) io.github.selenium.javaDriverless.fpmapping.DockerRuntimeProof"]
