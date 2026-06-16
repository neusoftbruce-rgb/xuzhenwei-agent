package com.xuzhenwei.agent.technique;

import com.xuzhenwei.agent.agent.ConversationManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * 技法注册中心 — 自动发现 Spring Bean 技法 + 从 techniques.yml 加载配置技法
 */
@Component
public class TechniqueRegistry {

    private static final Logger log = LoggerFactory.getLogger(TechniqueRegistry.class);

    private final Map<String, Technique> registry = new LinkedHashMap<>();

    private final ChatClient chatClient;
    private final ConversationManager conversationManager;

    @Value("classpath:techniques.yml")
    private Resource techniquesYaml;

    /** 暂存手写Bean，等YAML加载完再合并 */
    private final List<Technique> springBeans;

    public TechniqueRegistry(List<Technique> springBeans,
                             ChatClient chatClient,
                             ConversationManager conversationManager) {
        this.springBeans = springBeans;
        this.chatClient = chatClient;
        this.conversationManager = conversationManager;
    }

    /**
     * 在所有依赖注入完成后初始化——先注册手写Bean，再加YAML
     */
    @PostConstruct
    public void init() {
        // 先加载手写 Bean
        for (Technique t : springBeans) {
            registry.put(t.getId(), t);
        }

        // 再从 YAML 加载
        int yamlCount = loadFromYaml();

        log.info("技法注册完成：手写 {} 条 + YAML {} 条 = 共 {} 条",
                springBeans.size(), yamlCount, registry.size());
    }

    @SuppressWarnings("unchecked")
    private int loadFromYaml() {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(techniquesYaml.getInputStream());
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("techniques");

            if (list == null) {
                log.warn("techniques.yml 中没有找到 techniques 列表");
                return 0;
            }

            int loaded = 0;
            for (Map<String, Object> item : list) {
                String id = (String) item.get("id");
                if (registry.containsKey(id)) continue;

                TechniqueFactory.TechniqueDef def = parseDef(item);
                ConfigurableTechnique tech = new ConfigurableTechnique(def, chatClient, conversationManager);
                registry.put(id, tech);
                loaded++;
            }
            log.info("从 YAML 加载了 {} 条技法", loaded);
            return loaded;

        } catch (Exception e) {
            log.error("读取 techniques.yml 失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private TechniqueFactory.TechniqueDef parseDef(Map<String, Object> item) {
        TechniqueFactory.TechniqueDef def = new TechniqueFactory.TechniqueDef();
        def.setId((String) item.get("id"));
        def.setName((String) item.get("name"));
        def.setCategory((String) item.get("category"));
        def.setDescription((String) item.get("description"));
        def.setStepCount(getInt(item, "stepCount"));

        List<TechniqueFactory.StepDef> steps = new ArrayList<>();
        List<Map<String, Object>> stepList = (List<Map<String, Object>>) item.get("steps");
        if (stepList != null) {
            for (Map<String, Object> s : stepList) {
                TechniqueFactory.StepDef sd = new TechniqueFactory.StepDef();
                sd.setName((String) s.get("name"));
                sd.setPrompt((String) s.get("prompt"));
                steps.add(sd);
            }
        }
        def.setSteps(steps);
        return def;
    }

    private int getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    // ---- 查询方法 ----

    public Optional<Technique> get(String id) {
        return Optional.ofNullable(registry.get(id));
    }

    public List<Technique> getAll() {
        return registry.values().stream()
                .sorted(Comparator.comparing(Technique::getId))
                .toList();
    }

    public List<Technique> getByCategory(String category) {
        return registry.values().stream()
                .filter(t -> t.getCategory().equals(category))
                .sorted(Comparator.comparing(Technique::getId))
                .toList();
    }

    public List<String> getCategories() {
        return registry.values().stream()
                .map(Technique::getCategory)
                .distinct()
                .sorted()
                .toList();
    }

    public int count() {
        return registry.size();
    }

    public Technique recommend(String userInput) {
        return registry.getOrDefault("003", registry.values().iterator().next());
    }
}
