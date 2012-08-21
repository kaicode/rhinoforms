package com.rhinoforms;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;

import org.htmlcleaner.ContentNode;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.SimpleHtmlSerializer;
import org.htmlcleaner.TagNode;
import org.htmlcleaner.XPatherException;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.rhinoforms.resourceloader.ResourceLoader;
import com.rhinoforms.resourceloader.ResourceLoaderException;
import com.rhinoforms.serverside.InputPojo;

public class FormParser {

	private static final FieldPathHelper fieldPathHelper = new FieldPathHelper();
	final Logger logger = LoggerFactory.getLogger(FormParser.class);

	private SelectOptionHelper selectOptionHelper;
	private ProxyFactory proxyFactory;
	private ValueInjector valueInjector;
	private HtmlCleaner htmlCleaner;

	public FormParser(ResourceLoader resourceLoader) {
		this.selectOptionHelper = new SelectOptionHelper(resourceLoader);
		this.proxyFactory = new ProxyFactory();
		this.valueInjector = new ValueInjector();
		this.htmlCleaner = new HtmlCleaner();
	}

	public void parseForm(String formContents, FormFlow formFlow, PrintWriter writer, JSMasterScope masterScope) throws XPatherException,
			XPathExpressionException, IOException, ResourceLoaderException {

		TagNode formHtml = htmlCleaner.clean(formContents);

		Document dataDocument = formFlow.getDataDocument();
		String docBase = formFlow.getDocBase();
		String currentPath = formFlow.getCurrentPath();

		// Process rf.forEach statements
		valueInjector.processForEachStatements(formHtml, dataDocument, docBase);
		valueInjector.processRemainingCurlyBrackets(formHtml, dataDocument, docBase);

		Object[] rfFormNodes = formHtml.evaluateXPath("//form[@" + Constants.RHINOFORMS_FLAG + "='true']");
		if (rfFormNodes.length > 0) {
			logger.debug("{} forms found.", rfFormNodes.length);
			TagNode formNode = (TagNode) rfFormNodes[0];

			// Process dynamic select elements
			Object[] dynamicSelectNodes = formNode.evaluateXPath("//select[@" + Constants.SELECT_SOURCE_ATTR + "]");
			for (Object dynamicSelectNodeO : dynamicSelectNodes) {
				TagNode dynamicSelectNode = (TagNode) dynamicSelectNodeO;
				String name = dynamicSelectNode.getAttributeByName(Constants.NAME_ATTR);
				String source = dynamicSelectNode.getAttributeByName(Constants.SELECT_SOURCE_ATTR);
				dynamicSelectNode.removeAttribute(Constants.SELECT_SOURCE_ATTR);
				logger.debug("Found dynamicSelectNode name:{}, source:{}", name, source);

				List<SelectOptionPojo> options = selectOptionHelper.loadOptions(source);
				options.add(0, new SelectOptionPojo("-- Please Select --", ""));
				for (SelectOptionPojo selectOptionPojo : options) {
					TagNode optionNode = new TagNode("option");
					String value = selectOptionPojo.getValue();
					if (value != null) {
						optionNode.setAttribute("value", value);
					}
					optionNode.addChild(new ContentNode(selectOptionPojo.getText()));
					dynamicSelectNode.addChild(optionNode);
				}
			} // TODO: validate that submitted value comes from the list

			// Process range select elements
			Object[] rangeSelectNodes = formNode.evaluateXPath("//select[@" + Constants.SELECT_RANGE_START_ATTR + "]");
			if (rangeSelectNodes.length > 0) {
				Scriptable workingScope = masterScope.createWorkingScope();
				Context context = masterScope.getCurrentContext();
				for (Object rangeSelectNodeO : rangeSelectNodes) {
					TagNode rangeSelectNode = (TagNode) rangeSelectNodeO;
					String name = rangeSelectNode.getAttributeByName(Constants.NAME_ATTR);
					String rangeStart = rangeSelectNode.getAttributeByName(Constants.SELECT_RANGE_START_ATTR);
					String rangeEnd = rangeSelectNode.getAttributeByName(Constants.SELECT_RANGE_END_ATTR);
					rangeSelectNode.removeAttribute(Constants.SELECT_RANGE_START_ATTR);
					rangeSelectNode.removeAttribute(Constants.SELECT_RANGE_END_ATTR);
					logger.debug("Found rangeSelectNode name:{}, rangeStart:{}, rangeEnd:{}", new String[] { name, rangeStart, rangeEnd });
					boolean rangeStartValid = rangeStart != null && !rangeStart.isEmpty();
					boolean rangeEndValid = rangeEnd != null && !rangeEnd.isEmpty();
					if (rangeStartValid && rangeEndValid) {
						Object rangeStartResult = context.evaluateString(workingScope, "{" + rangeStart + "}",
								Constants.SELECT_RANGE_START_ATTR, 1, null);
						Object rangeEndResult = context.evaluateString(workingScope, "{" + rangeEnd + "}", Constants.SELECT_RANGE_END_ATTR,
								1, null);
						logger.debug("RangeSelectNode name:{}, rangeStartResult:{}, rangeEndResult:{}", new Object[] { name,
								rangeStartResult, rangeEndResult });

						double rangeStartResultNumber = Context.toNumber(rangeStartResult);
						double rangeEndResultNumber = Context.toNumber(rangeEndResult);
						String comparator;
						String incrementor;
						if (rangeStartResultNumber < rangeEndResultNumber) {
							comparator = "<";
							incrementor = "++";
						} else {
							comparator = ">";
							incrementor = "--";
						}

						String rangeStatement = "{ var range = []; for( var i = " + rangeStartResult + "; i " + comparator + " "
								+ rangeEndResult + "; i" + incrementor + ") { range.push(i); }; '' + range; }";
						logger.debug("RangeSelectNode name:{}, rangeStatement:{}", name, rangeStatement);
						String rangeResult = (String) context.evaluateString(workingScope, rangeStatement, "Calculate range", 1, null);
						logger.debug("RangeSelectNode name:{}, rangeResult:{}", name, rangeResult);

						for (String item : rangeResult.split(",")) {
							TagNode optionNode = new TagNode("option");
							optionNode.addChild(new ContentNode(item));
							rangeSelectNode.addChild(optionNode);
						}

					} else {
						logger.warn("Range select node '{}' not processed because {} is empty.", name,
								(rangeStartValid ? Constants.SELECT_RANGE_START_ATTR : Constants.SELECT_RANGE_END_ATTR));
					}
				}
			}

			// Record input fields
			List<InputPojo> inputPojos = new ArrayList<InputPojo>();
			Map<String, InputPojo> inputPojosMap = new HashMap<String, InputPojo>();

			@SuppressWarnings("unchecked")
			List<TagNode> inputs = formNode.getElementListByName("input", true);
			@SuppressWarnings("unchecked")
			List<TagNode> selects = formNode.getElementListByName("select", true);
			inputs.addAll(selects);
			for (TagNode inputTagNode : inputs) {
				String name = inputTagNode.getAttributeByName(Constants.NAME_ATTR);
				if (name != null) {
					String type;

					if (inputTagNode.getName().equals("select")) {
						type = "select";
					} else {
						type = inputTagNode.getAttributeByName(Constants.TYPE_ATTR);
					}

					if (!(type.equals("radio") && inputPojosMap.containsKey(name))) {

						// Collect all rf.xxx attributes
						Map<String, String> rfAttributes = new HashMap<String, String>();
						Map<String, String> attributes = inputTagNode.getAttributes();
						for (String attName : attributes.keySet()) {
							if (attName.startsWith("rf.")) {
								rfAttributes.put(attName, attributes.get(attName));
							}
						}

						InputPojo inputPojo = new InputPojo(name, type, rfAttributes);
						inputPojosMap.put(name, inputPojo);
						inputPojos.add(inputPojo);
					}

					// Push values from the dataDocument into the form html.
					String inputValue = lookupValueByFieldName(dataDocument, name, docBase);
					if (inputValue != null) {
						if (type.equals("radio")) {
							String value = inputTagNode.getAttributeByName(Constants.VALUE_ATTR);
							if (inputValue.equals(value)) {
								inputTagNode.setAttribute(Constants.CHECKED_ATTR, Constants.CHECKED_ATTR);
							}
						} else if (type.equals("checkbox")) {
							if (inputValue.equals("true")) {
								inputTagNode.setAttribute(Constants.CHECKED_ATTR, Constants.CHECKED_ATTR);
							}
						} else if (type.equals("select")) {
							Object[] node = inputTagNode.evaluateXPath("option[text()=\"" + inputValue + "\"]");
							if (node.length > 0) {
								((TagNode) node[0]).setAttribute(Constants.SELECTED_ATTR, "selected");
							}
						} else {
							inputTagNode.setAttribute("value", inputValue);
						}
					}
				}
			}
			formFlow.setCurrentInputPojos(inputPojos);

			// Process auto-complete fields, replace source with proxy path
			Object[] autoCompleteNodes = formNode.evaluateXPath("//input[@" + Constants.SELECT_SOURCE_ATTR + "]");
			for (Object autoCompleteNodeO : autoCompleteNodes) {
				TagNode autoCompleteNode = (TagNode) autoCompleteNodeO;
				String fieldName = autoCompleteNode.getAttributeByName(Constants.NAME_ATTR);
				String source = autoCompleteNode.getAttributeByName(Constants.INPUT_SOURCE_ATTR);

				FieldSourceProxy fieldSourceProxy = proxyFactory.createFlowProxy(currentPath, fieldName, source);
				formFlow.addFieldSourceProxy(fieldSourceProxy);
				autoCompleteNode.removeAttribute(Constants.INPUT_SOURCE_ATTR);
				autoCompleteNode.setAttribute("rf.source", "rhinoforms/proxy/" + fieldSourceProxy.getProxyPath());
			}

			// Add flowId as hidden field
			TagNode flowIdNode = new TagNode("input");
			flowIdNode.setAttribute("name", Constants.FLOW_ID_FIELD_NAME);
			flowIdNode.setAttribute("type", "hidden");
			flowIdNode.setAttribute("value", formFlow.getId() + "");
			formNode.insertChild(0, flowIdNode);

			// Mark form as parsed
			formNode.setAttribute("parsed", "true");
		} else {
			logger.warn("No forms found");
		}

		// Write out processed document
		new SimpleHtmlSerializer(htmlCleaner.getProperties()).write(formHtml, writer, "utf-8");
	}

	String lookupValueByFieldName(Node document, String name, String docBase) throws XPathExpressionException {
		String inputValue = null;
		XPathExpression xPathExpression = fieldPathHelper.fieldToXPathExpression(docBase, name);
		NodeList nodeList = (NodeList) xPathExpression.evaluate(document, XPathConstants.NODESET);
		if (nodeList != null && nodeList.getLength() == 1) {
			inputValue = nodeList.item(0).getTextContent();
		} else if (nodeList != null && nodeList.getLength() > 1) {
			logger.warn("Multiple nodes matched for documentBasePath: '{}', field name: '{}"
					+ "'. No value will be pushed into the form and there may be submission problems.", docBase, name);
		}
		return inputValue;
	}

}
