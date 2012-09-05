package com.rhinoforms;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.ScriptableObject;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.rhinoforms.resourceloader.ResourceLoader;

public class FormFlowFactory {

	private ResourceLoader resourceLoader;
	private DocumentBuilderFactory documentBuilderFactory;
	private DocumentHelper documentHelper;

	public FormFlowFactory(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
		this.documentBuilderFactory = DocumentBuilderFactory.newInstance();
		this.documentHelper = new DocumentHelper();
	}

	public FormFlow createFlow(String formFlowPath, Context jsContext, String dataDocumentString) throws IOException,
			FormFlowFactoryException {

		try {
			ScriptableObject scope = jsContext.initStandardObjects();
			FormFlow formFlow = new FormFlow();
			
			String resourcesBase = "";
			if (formFlowPath.contains("/")) {
				resourcesBase = formFlowPath.substring(0, formFlowPath.lastIndexOf("/") + 1);
			}
			if (!resourcesBase.isEmpty() && resourcesBase.charAt(0) != '/') {
				resourcesBase = '/' + resourcesBase;
			}
			formFlow.setResourcesBase(resourcesBase);

			Object wrappedFormFlow = Context.javaToJS(formFlow, scope);
			ScriptableObject.putProperty(scope, "formFlow", wrappedFormFlow);
			String scriptPath = "/flow-loader.js";
			jsContext.evaluateReader(scope, new InputStreamReader(FormFlowFactory.class.getResourceAsStream(scriptPath)), scriptPath, 1,
					null);

			InputStreamReader inputStreamReader = new InputStreamReader(resourceLoader.getResourceAsStream(formFlowPath));
			BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
			StringBuilder stringBuilder = new StringBuilder();
			stringBuilder.append("loadFlow(");
			while (bufferedReader.ready()) {
				stringBuilder.append(bufferedReader.readLine());
			}
			stringBuilder.append(")");

			String newFlowJsExpresion = stringBuilder.toString();
			jsContext.evaluateString(scope, newFlowJsExpresion, formFlowPath, 1, null);

			String flowDocBase = formFlow.getFlowDocBase();
			if (flowDocBase != null) {

				// Parse or create initial document. Make sure flow docBase node is there.
				DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
				Document dataDocument = null;
				if (dataDocumentString != null && !dataDocumentString.isEmpty()) {
					dataDocument = documentBuilder.parse(new ByteArrayInputStream(dataDocumentString.getBytes()));
				} else {
					dataDocument = documentBuilder.newDocument();
				}

				documentHelper.createNodeIfNotThere(dataDocument, flowDocBase);

				formFlow.setDataDocument(dataDocument);

				return formFlow;
			} else {
				throw new FormFlowFactoryException("Please specify a form-flow docBase.");
			}
		} catch (EvaluatorException e) {
			throw new FormFlowFactoryException("Error parsing flow js file.", e);
		} catch (ParserConfigurationException e) {
			throw new FormFlowFactoryException("Error parsing initial data document.", e);
		} catch (SAXException e) {
			throw new FormFlowFactoryException("Error parsing initial data document.", e);
		} catch (DocumentHelperException e) {
			throw new FormFlowFactoryException("Error creating base node in data document using flow docBase.", e);
		}
	}

}
