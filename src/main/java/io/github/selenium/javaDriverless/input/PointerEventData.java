package io.github.selenium.javaDriverless.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

class PointerModifiers {
    public static final int NONE = 0;
    public static final int ALT = 1;
    public static final int CTRL = 2;
    public static final int COMMAND = 4;
    public static final int SHIFT = 8;
}

class PointerTypes {
    public static final String MOUSE = "mouse";
    public static final String PEN = "pen";
}

class PointerMouseButtons {
    public static final String NONE = "none";
    public static final String LEFT = "left";
    public static final String MIDDLE = "middle";
    public static final String RIGHT = "right";
    public static final String BACK = "back";
    public static final String FORWARD = "forward";
}

class PointerButtonMasks {
    public static final Integer NONE = 0;
    public static final Integer LEFT = 1;
    public static final Integer RIGHT = 2;
    public static final Integer MIDDLE = 4;
    public static final Integer BACK = 8;
    public static final Integer FORWARD = 16;
    public static final Integer DEFAULT = null;

    static int forButton(String button) {
        if (PointerMouseButtons.LEFT.equals(button)) {
            return LEFT;
        } else if (PointerMouseButtons.RIGHT.equals(button)) {
            return RIGHT;
        } else if (PointerMouseButtons.MIDDLE.equals(button)) {
            return MIDDLE;
        } else if (PointerMouseButtons.BACK.equals(button)) {
            return BACK;
        } else if (PointerMouseButtons.FORWARD.equals(button)) {
            return FORWARD;
        }
        return NONE;
    }
}

class PointerEventTypes {
    public static final String PRESS = "mousePressed";
    public static final String RELEASE = "mouseReleased";
    public static final String MOVE = "mouseMoved";
    public static final String WHEEL = "mouseWheel";
}

class PointerEventData {
    private static final String COMMAND = "Input.dispatchMouseEvent";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public String type;
    public int x;
    public int y;
    public int modifiers = PointerModifiers.NONE;
    public Double timestamp;
    public String button = PointerMouseButtons.LEFT;
    public Integer buttons = PointerButtonMasks.DEFAULT;
    public int clickCount = 0;
    public double force = 0;
    public double tangentialPressure = 0;
    public double tiltX = 0;
    public double tiltY = 0;
    public double twist = 0;
    public int deltaX = 0;
    public int deltaY = 0;
    public String pointerType = PointerTypes.MOUSE;

    PointerEventData(String type, int x, int y) {
        this.type = type;
        this.x = x;
        this.y = y;
    }

    /**
     * Converte o evento para comando CDP.
     *
     * @return array [comando, parâmetros]
     */
    public Object[] toJson() {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("type", type);
        params.put("x", x);
        params.put("y", y);
        params.put("modifiers", modifiers);
        params.put("button", button);
        params.put("clickCount", clickCount);
        params.put("force", force);
        params.put("tangentialPressure", tangentialPressure);
        params.put("tiltX", tiltX);
        params.put("tiltY", tiltY);
        params.put("twist", twist);
        params.put("deltaX", deltaX);
        params.put("deltaY", deltaY);
        params.put("pointerType", pointerType);

        if (timestamp != null) {
            params.put("timestamp", timestamp);
        }
        if (buttons != null) {
            params.put("buttons", buttons);
        }

        return new Object[]{COMMAND, params};
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(Object params) {
        return OBJECT_MAPPER.convertValue(params, Map.class);
    }
}
