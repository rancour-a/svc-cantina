package com.ccex.SystemViewController;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.ResourceUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

@SpringBootApplication
public class SystemViewControllerApplication {

	final Logger log = LoggerFactory.getLogger(getClass());
	boolean prettyPrint;

	final static String TREE_ENTRY_POINT = "subviews";
	final static String[] SELECTORS = new String[] { "class", "classNames", "identifier" };

	@Value("${ccex.jsonToParse.Url}")
	String dataLocationUrl;

	public static void main(String[] args) {
		SpringApplication.run(SystemViewControllerApplication.class, args);
	}

	@Bean
	public CommandLineRunner run() throws Exception {
		return (String[] args) -> {

			List<String> parameters = Arrays.asList(args);
			if (log.isDebugEnabled()) {
				log.debug("Running SystemViewController Demo with parameters {}", parameters);
			}

			// retrieve data file, given parameters
			byte[] jsonData = getDataFile(parameters);

			Map<String, Set<JsonNode>> selectorMap = new HashMap<>();
			JsonNode rootNode = new ObjectMapper().readTree(jsonData);
			// parse the data-file into selectorMap (recursion)
			traverseNodes(rootNode.path(TREE_ENTRY_POINT).elements(), selectorMap);

			if (log.isDebugEnabled()) {
				log.debug("JSON has been parsed and mapped {}", selectorMap);
			}

			// attain selectors via user input / SYS IN
			Scanner scanner = new Scanner(System.in);
			while (true) {
				System.out.println("-----------");
				System.out.print("Selector(s) \"?\" for options : ");
				String userInput = scanner.nextLine().trim().replaceAll(" ", "");

				if ("?".equals(userInput)) {
					System.out.println(" >> \"q\" to quit");
					System.out.println(" >> \"y\" to PrettyPrint");
					System.out.println(" >> \"n\" to Not PrettyPrint");
					System.out.println(" >> \"s\" to display parsed dataStore");
					continue;
				}

				if ("q".equals(userInput)) {
					scanner.close();
					System.out.println("BYE!");
					System.exit(0);
				} else if ("y".equals(userInput)) {
					prettyPrint = true;
					System.out.println("PrettyPrint is ON");
					continue;
				} else if ("n".equals(userInput)) {
					prettyPrint = false;
					System.out.println("PrettyPrint is OFF");
					continue;
				} else if ("s".equals(userInput)) {
					if (!prettyPrint) {
						System.out.println("Data Store: " + selectorMap);
					} else {
						System.out.println("Data Store: "
								+ new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(selectorMap));
					}
					continue;
				}

				System.out.println("You entered : [" + userInput + "]");
				printOutput(userInput, selectorMap);
			}
		};
	}

	private byte[] getDataFile(List<String> parameters) {
		byte[] data;
		try {
			if (!parameters.isEmpty()) { // passed in via command line 1st parameter
				data = Files.readAllBytes(Paths.get(parameters.get(0)));
				if (log.isDebugEnabled()) {
					log.debug("CMD line file loaded {}", parameters.get(0));
				}
			} else { // Nothing passed in, get it from internal application resource
				// (src/main/resources)
				data = Files.readAllBytes(ResourceUtils.getFile("classpath:SystemViewController.json").toPath());
				if (log.isDebugEnabled()) {
					log.debug("App resource file loaded from src/main/resources");
				}
			}
		} catch (Exception e) {
			// default >> unable to retrieve file (bad file passed in) - get it remotely
			data = restTemplate().getForObject(dataLocationUrl, String.class).getBytes();

			if (log.isDebugEnabled()) {
				log.debug("Default file loaded from github.");
			}
		}
		return data;
	}

	private void traverseNodes(Iterator<JsonNode> element, Map<String, Set<JsonNode>> selectorMap)
			throws JsonProcessingException {
		while (element.hasNext()) {
			JsonNode node = element.next();
			// if node is json and hasSelector(s), add to map of selectors
			if (node.isContainerNode() && !node.isArray()) {
				getSelector(node).forEach(selector -> addNodeToMap(selector, node, selectorMap));
			}
			// drill
			traverseNodes(node.elements(), selectorMap);
		}
	}

	private void addNodeToMap(String selector, JsonNode node, Map<String, Set<JsonNode>> selectorMap) {
		if (selectorMap.containsKey(selector)) {
			// already in map, add to list
			Set<JsonNode> preExistingList = selectorMap.get(selector);
			preExistingList.add(node);
			selectorMap.put(selector, preExistingList);
		} else {
			// new selector, add
			selectorMap.put(selector, new HashSet<>(Arrays.asList(node)));
		}
	}


	@SuppressWarnings("unchecked")
	private List<String> getSelector(JsonNode element) throws JsonProcessingException {

		// sends Json to map to ease lookup
		Map<String, Object> jsonMap = new ObjectMapper().treeToValue(element, HashMap.class);

		// determines the search keys
		List<String> selectorValues = new ArrayList<>();
		for (String selector : SELECTORS) {
			if (jsonMap.containsKey(selector)) {
				if (jsonMap.get(selector) instanceof String) {
					selectorValues.add("" + jsonMap.get(selector));
				} else { // this case resolves the classNames being arrays instead of strings
					((ArrayList<String>) jsonMap.get(selector)).forEach(select -> selectorValues.add(select));
				}
			}
		}
		return selectorValues;
	}

	public RestTemplate restTemplate() {
		RestTemplate restTemplate = new RestTemplate();
		List<HttpMessageConverter<?>> converters = restTemplate.getMessageConverters();
		for (HttpMessageConverter<?> converter : converters) {
			if (converter instanceof MappingJackson2HttpMessageConverter) {
				MappingJackson2HttpMessageConverter jsonConverter = (MappingJackson2HttpMessageConverter) converter;
				jsonConverter.setObjectMapper(new ObjectMapper());
				// this allows restTemplate to pull in JSON objects
				jsonConverter.setSupportedMediaTypes(ImmutableList
						.of(new MediaType("*", "json", MappingJackson2HttpMessageConverter.DEFAULT_CHARSET)));
			}
		}
		return restTemplate;
	}

	private void printOutput(String userInput, Map<String, Set<JsonNode>> selectorMap) throws JsonProcessingException {

		Set<JsonNode> totalSelected = new HashSet<>();
		List<String> inSelectors;

		if (userInput.contains("#")) {
			inSelectors = Arrays.asList(userInput.split("#"));
		} else if (userInput.contains(".")) {
			System.out.println(" Compounding selectors UNSUPPORTED (.)   Sorry.");
			return;
			// inSelectors = Arrays.asList(userInput.split("\\."));
		} else {
			inSelectors = Arrays.asList(userInput);
		}

		// combine all JSONs together / set
		inSelectors.forEach(selection -> {
			if (selectorMap.get(selection) != null) {
				if (log.isDebugEnabled()) {
					log.debug("[{}] views for selector [{}]", selectorMap.get(selection).size(), selection);
				}
				totalSelected.addAll(selectorMap.get(selection));
			}
		});

		// // for compounds (.) filter
		// if (userInput.contains(".")) {
		// // traverse the totalSelected JSONS, and make sure all have all the
		// selectors,
		// // filtering those out that don't
		// Set<JsonNode> compoundingList = new HashSet<>();
		// for (JsonNode node : totalSelected) {
		// if (isValidNode(node, inSelectors)) {
		// compoundingList.add(node);
		// }
		// }
		// // filters down
		// if (!compoundingList.isEmpty()) {
		// if (log.isDebugEnabled()) {
		// log.debug("Compounded List: {}", compoundingList);
		// }
		// totalSelected.retainAll(compoundingList);
		// }
		// }

		// print to STD OUT
		if (!prettyPrint) {
			System.out.println("Views (" + totalSelected.size() + "): " + totalSelected);
		} else {
			System.out.println("Views (" + totalSelected.size() + "): "
					+ new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(totalSelected));
		}

	}

	// private boolean isValidNode(JsonNode element, List<String> inSelectors)
	// throws JsonProcessingException {
	//
	// // sends Json to map to ease lookup
	// Map<String, Object> jsonMap = new ObjectMapper().treeToValue(element,
	// HashMap.class);
	//
	// // search keys
	// for (String selector : inSelectors) {
	// if (!jsonMap.containsKey(selector)) {
	// return false;
	// }
	// }
	// return true;
	// }
}
