package com.xuzhenwei.agent.technique;

import com.xuzhenwei.agent.agent.ConversationManager;
import java.util.ArrayList;
import java.util.List;

/**
 * 技法数据类 — TechniqueDef 和 StepDef，供 Registry 从 YAML 反序列化使用
 */
public class TechniqueFactory {

    // ---- 数据类 ----

    public static class TechniqueDef {
        private String id;
        private String name;
        private String category;
        private String description;
        private int stepCount;
        private List<StepDef> steps = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getStepCount() { return stepCount; }
        public void setStepCount(int stepCount) { this.stepCount = stepCount; }
        public List<StepDef> getSteps() { return steps; }
        public void setSteps(List<StepDef> steps) { this.steps = steps; }
    }

    public static class StepDef {
        private String name;
        private String prompt;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
    }
}
