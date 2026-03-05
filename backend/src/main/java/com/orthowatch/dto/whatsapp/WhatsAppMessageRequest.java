package com.orthowatch.dto.whatsapp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhatsAppMessageRequest {

  @JsonProperty("messaging_product")
  @Builder.Default
  private String messagingProduct = "whatsapp";

  @JsonProperty("recipient_type")
  @Builder.Default
  private String recipientType = "individual";

  private String to;

  private String type;

  private TextBody text;

  private TemplateBody template;

  private InteractiveBody interactive;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TextBody {
    private String body;

    @JsonProperty("preview_url")
    private Boolean previewUrl;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TemplateBody {
    private String name;

    private Language language;

    private List<Component> components;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Language {
      private String code;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Component {
      private String type;

      private List<Parameter> parameters;

      @Data
      @Builder
      @NoArgsConstructor
      @AllArgsConstructor
      public static class Parameter {
        private String type;

        private String text;
      }
    }
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class InteractiveBody {
    private String type;

    private InteractiveContent body;

    private InteractiveAction action;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InteractiveContent {
      private String text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InteractiveAction {
      private List<Button> buttons;

      @Data
      @Builder
      @NoArgsConstructor
      @AllArgsConstructor
      public static class Button {
        private String type;

        private ButtonReply reply;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ButtonReply {
          private String id;

          private String title;
        }
      }
    }
  }
}
