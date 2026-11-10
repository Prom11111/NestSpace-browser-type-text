package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class PluginSettingsField {

    public enum Type {
        TEXT,
        BOOLEAN,
        INTEGER,
        SELECT,
        SECRET
    }

    public enum Format {
        NONE,
        URL,
        EMAIL,
        HOST
    }

    private final String id;
    private final String title;
    private final String description;
    private final String placeholder;
    private final Type type;
    private final Format format;
    private final String defaultValue;
    private final boolean required;
    private final List<String> options;
    private final String pattern;
    private final String patternMessage;
    private final Pattern compiledPattern;
    private final int minLength;
    private final int maxLength;
    private final Long minValue;
    private final Long maxValue;

    private PluginSettingsField(
        String id,
        String title,
        String description,
        String placeholder,
        Type type,
        Format format,
        String defaultValue,
        boolean required,
        List<String> options,
        String pattern,
        String patternMessage,
        int minLength,
        int maxLength,
        Long minValue,
        Long maxValue
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = title == null ? "" : title.trim();
        this.description = description == null ? "" : description.trim();
        this.placeholder = placeholder == null ? "" : placeholder.trim();
        this.type = Objects.requireNonNull(type, "type");
        this.format = format == null ? Format.NONE : format;
        this.defaultValue = defaultValue == null ? "" : defaultValue;
        this.required = required;
        this.options = options == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(options));
        this.pattern = pattern == null ? "" : pattern;
        this.patternMessage = patternMessage == null
            ? ""
            : patternMessage.trim();
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.minValue = minValue;
        this.maxValue = maxValue;
        if (!this.id.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("invalid settings field id");
        }
        if (this.title.isEmpty()) {
            throw new IllegalArgumentException(
                "settings field title is required"
            );
        }
        if (type == Type.SELECT && this.options.isEmpty()) {
            throw new IllegalArgumentException(
                "select settings field requires options"
            );
        }
        if (minLength < 0 || maxLength < minLength) {
            throw new IllegalArgumentException(
                "invalid settings field length range"
            );
        }
        if (minValue != null && maxValue != null && minValue > maxValue) {
            throw new IllegalArgumentException(
                "invalid settings field value range"
            );
        }
        if (this.pattern.isEmpty()) {
            this.compiledPattern = null;
        } else {
            try {
                this.compiledPattern = Pattern.compile(this.pattern);
            } catch (PatternSyntaxException error) {
                throw new IllegalArgumentException(
                    "invalid settings field pattern: " + error.getMessage(),
                    error
                );
            }
        }
        if (!this.defaultValue.isEmpty()) {
            String error = validate(this.defaultValue);
            if (!error.isEmpty()) {
                throw new IllegalArgumentException(
                    "invalid default for settings field " + this.id +
                        ": " + error
                );
            }
        }
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public Type getType() {
        return type;
    }

    public Format getFormat() {
        return format;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public boolean isRequired() {
        return required;
    }

    public List<String> getOptions() {
        return options;
    }

    public String getPattern() {
        return pattern;
    }

    public String getPatternMessage() {
        return patternMessage;
    }

    public int getMinLength() {
        return minLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public Long getMinValue() {
        return minValue;
    }

    public Long getMaxValue() {
        return maxValue;
    }

    /**
     * Validates one submitted value. Returns an empty string when valid.
     *
     * Secret values are validated only when non-empty; required-secret checks
     * remain the host's responsibility because an existing secret is not
     * rendered back into the form.
     */
    public String validate(String rawValue) {
        String value = rawValue == null ? "" : rawValue;
        if (required && value.trim().isEmpty()) {
            return title + " is required";
        }
        if (value.isEmpty()) return "";
        if (type == Type.INTEGER) {
            long parsed;
            try {
                parsed = Long.parseLong(value.trim());
            } catch (NumberFormatException error) {
                return title + " must be an integer";
            }
            if (minValue != null && parsed < minValue) {
                return title + " must be at least " + minValue;
            }
            if (maxValue != null && parsed > maxValue) {
                return title + " must be at most " + maxValue;
            }
            return "";
        }
        if (type == Type.BOOLEAN) {
            return "true".equalsIgnoreCase(value) ||
                "false".equalsIgnoreCase(value)
                ? ""
                : title + " must be true or false";
        }
        if (type == Type.SELECT && !options.contains(value)) {
            return title + " has an invalid option";
        }
        if (type == Type.TEXT || type == Type.SECRET) {
            if (value.length() < minLength) {
                return title + " must contain at least " +
                    minLength + " characters";
            }
            if (value.length() > maxLength) {
                return title + " must contain at most " +
                    maxLength + " characters";
            }
        }
        if (format == Format.URL &&
            !value.matches("(?i)^https?://[^\\s]+$")
        ) {
            return title + " must be an HTTP(S) URL";
        }
        if (format == Format.EMAIL &&
            !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        ) {
            return title + " must be an email address";
        }
        if (format == Format.HOST &&
            !value.matches("^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")
        ) {
            return title + " must be a host name";
        }
        if (compiledPattern != null && !compiledPattern.matcher(value).matches()) {
            return patternMessage.isEmpty()
                ? title + " has an invalid format"
                : patternMessage;
        }
        return "";
    }

    public static final class Builder {
        private final String id;
        private final String title;
        private String description = "";
        private String placeholder = "";
        private final Type type;
        private Format format = Format.NONE;
        private String defaultValue = "";
        private boolean required;
        private List<String> options = Collections.emptyList();
        private String pattern = "";
        private String patternMessage = "";
        private int minLength = 0;
        private int maxLength = PluginSettingsApi.MAX_STRING_CHARS;
        private Long minValue;
        private Long maxValue;

        public Builder(String id, String title, Type type) {
            this.id = id;
            this.title = title;
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder placeholder(String value) {
            this.placeholder = value;
            return this;
        }

        public Builder format(Format value) {
            this.format = value == null ? Format.NONE : value;
            return this;
        }

        public Builder defaultValue(String value) {
            this.defaultValue = value;
            return this;
        }

        public Builder required(boolean value) {
            this.required = value;
            return this;
        }

        public Builder options(List<String> value) {
            this.options = value;
            return this;
        }

        public Builder pattern(String value, String message) {
            this.pattern = value;
            this.patternMessage = message;
            return this;
        }

        public Builder length(int min, int max) {
            this.minLength = min;
            this.maxLength = max;
            return this;
        }

        public Builder range(Long min, Long max) {
            this.minValue = min;
            this.maxValue = max;
            return this;
        }

        public PluginSettingsField build() {
            return new PluginSettingsField(
                id,
                title,
                description,
                placeholder,
                type,
                format,
                defaultValue,
                required,
                options,
                pattern,
                patternMessage,
                minLength,
                maxLength,
                minValue,
                maxValue
            );
        }
    }
}
