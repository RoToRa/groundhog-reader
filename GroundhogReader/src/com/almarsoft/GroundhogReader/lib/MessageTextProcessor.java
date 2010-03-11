package com.almarsoft.GroundhogReader.lib;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringEscapeUtils;

import android.util.Log;

public class MessageTextProcessor {
    // =============================================================================
    // Remove all \n except the ones that are after a point. We do this
    // because the screen is probably going to be less than 70 chars anyway, so 
    // we let the GUI reflow the new lines, which makes the text looks "justified".
    // =============================================================================

    public static String sanitizeLineBreaks(String inputText) {
    	
    	if (inputText == null) 
    		return null;
    	
    	StringBuilder newBody = new StringBuilder(inputText.length());
    	char charcur, charprev, charnext, selectedChar;
    	StringBuilder previousLine = new StringBuilder();
    	int inputTextLen = inputText.length();
    	boolean hasNext;
    	
    	for (int i = 0; i < inputTextLen; i++) {
    		
    		previousLine.append(inputText.charAt(i));
    		selectedChar = inputText.charAt(i);
    		
    		if (i > 1) {
    			charcur  = inputText.charAt(i);
    			charprev = inputText.charAt(i-1);
    			
    			if (inputText.length() > i+1) {
    				charnext = inputText.charAt(i+1);
    				hasNext = true;
    			}
    			else {
    				charnext = charcur;
    				hasNext = false;
    			}
    			
    			if (charcur == '\n') {
    				/* We want to eliminate artificial newlines, used by the newsreaders to comply with
    				 * the 70-chars long rule. But we don't want to remove good placed newlines, so our heuristic
    				 * rules are:
    				 * 
    				 * 1. Dont justify lines next to quoted text
    				 * 2. Dont remove newlines close to '.', '!', '?' (natural line terminators)
    				 * 3. Dont remove newlines that have newlines before or after it (formatting newlines)
    				 */
					if( previousLine.charAt(0) != '>'
						&&(charprev != '.')
					    && (charprev != '!')
					    && (charprev != ':')
						&& (charprev != '?')
						&& (!inputText.substring(i-2, i).equals("> "))
						&& (charprev != '\n') // Don't remote newlines when there are more than one together (formatting newlines)
						&& (hasNext && charnext != '\n')) {
						
						selectedChar = ' ';
					}
					
					if (inputText.substring(i-2, i).equals("--")) {
						selectedChar = '\n';
					}
					
					previousLine = new StringBuilder();
    			}
    		}
			newBody.append(selectedChar);
    	}
    	
    	return newBody.toString();
    }
	
    
    
    public static String prepareHTML(String inputText) {
    	
    	StringBuilder html = new StringBuilder(inputText.length());

    	String[] lines = inputText.split("\n");
    	String line;
    	boolean lastWasQuote = false;
    	String quoteColor;
    	int lastQuoteColor = -1;
    	int linesLen = lines.length;
    	int colorhash;
    	//StringWriter ws = new StringWriter();
    	
    	for (int i=0; i < linesLen; i++) {
    		
    		line = lines[i];
    		
    		// Remove empty quoting lines like ">\n" and ">> \n"
    		
    		if (isEmptyQuoting(line)) {
    			html.append("<BR/>");
    			continue;
    		}
    		
    		if (line.length() > 1 && (line.charAt(0) == '>')) {
    			// We're in quote 
    			quoteColor = getQuotingColor(line);
    			
    			colorhash = quoteColor.hashCode();
    			if (lastWasQuote && (colorhash == lastQuoteColor)) {
    				// We're at the same quoting level; use a <BR> so we don't break the paragraph
    				html.append("</I> <BR/>\n<I>");
    				lastWasQuote = true;
    				
    			} else {
    				// First line of different quoting level; use a </P> and change the color
    				if (lastWasQuote) html.append("</I>");
    				
    				html.append("<P/>\n<P style=\"BACKGROUND-COLOR: ");
    				html.append(quoteColor);
					html.append("\"><I>");
    							
    				lastWasQuote = true;
    				lastQuoteColor = colorhash;
    			}
    		}
    		else { 
    			if (lastWasQuote) {
    				// We're not in quote and last was quote, close the <i>
    				html.append("</I></P>");
    				lastWasQuote = false;
    			}
    			html.append("<P>\n");
    			
    		}
    		html.append(escapeHtmlWithLinks(line));
    	}
    	
    	html.append("\n</BODY> </HTML>\n");
    	return html.toString();
    }
    
    // =================================================================================================
    // Escape a text converting it to HTML. It also convert urls to links; this conversion is not
    // the most advances: it only works when the link is entirely contained within the same line and it
    // only converts the first link in the line. But it covers 95% of cases found on Usenet.
    // =================================================================================================
    private static String escapeHtmlWithLinks(String line) {
    	
    	// Shortcut for most cases with line not having a link
    	StringBuffer buf = null;
    	
    	int idx = line.toLowerCase().indexOf("http://");
    	
    	if (idx == -1) {
    		return StringEscapeUtils.escapeHtml(line);
    	} else {
    		buf = new StringBuffer();
    		buf.append(StringEscapeUtils.escapeHtml(line.substring(0, idx)));
    		
    		char c;
    		String endLine;
    		StringBuffer urlBuf = new StringBuffer();
    		int lineLen = line.length();
    		
    		for(;idx < lineLen; idx++) {
    			c = line.charAt(idx);
    			if (Character.isSpace(c)) {
    				break;
    			}
    			urlBuf.append(c);
    		}
    		
    		if (urlBuf.length() > 0) {
    			buf.append("<A HREF=\""); buf.append(urlBuf); buf.append("\" >"); buf.append(urlBuf); buf.append("</A> ");
    		}
    		
    		endLine = line.substring(idx);
    		if (endLine.length() > 0)
    			buf.append(StringEscapeUtils.escapeHtml(line.substring(idx)));
    	}
    	
    	return buf.toString();
    }
	
	
    private static boolean isEmptyQuoting(String line) {
    	line = line.trim();
    	boolean emptyQuote = true;
    	int lineLen = line.length();
    	
    	for(int i=0; i<lineLen; i++) {
    		if (line.charAt(i) != '>') {
    			emptyQuote = false;
    			break;
    		}
    	}
		return emptyQuote;
	}


	private static String getQuotingColor(String line) {
    	String color = "yellow";
    	
    	int count = 0;
    	int lineLen = line.length();
    	
    	for (int i = 0; i<lineLen;i++) {
    		if      (line.charAt(i) == ' ') continue; 
    		else if (line.charAt(i) != '>') break; 
    		count++;
    	}
    	
    	if      (count == 1) color = "palegreen";
    	else if (count == 2) color = "lightblue";
    	else if (count == 3) color = "lightcoral";
    	else if (count >= 4) color = "violet";
    	return color;
    }
	
	
	// ============================================================================================
	// Replace in the configured quote header template the replacing tokens with the real values.
	// This doesn't validate for null values so make sure the arguments are not null before calling
	// this function
	// ============================================================================================
	private static String parseQuoteHeaderTemplate(String template, String from, String date) {
		
		String retVal = template;
		retVal = retVal.replace("[user]", from);
		retVal = retVal.replace("[date]", date);
		
		return retVal;
	}
	


	// ======================================================
	// Takes the original body and adds the quotes
	// ======================================================
	public static String quoteBody(String origText, String quoteheader, String from, String date) {
		
		String[] tmpLines = origText.split("\n");
		ArrayList<String> list = new ArrayList<String>(tmpLines.length+2);
		
		// Add the attribution
		
		if (from != null && date != null && quoteheader != null) {
			//list.add("On " + date + " " + from + " wrote:\n");
			list.add(parseQuoteHeaderTemplate(quoteheader, from, date));
		} 
		
		boolean startWhiteSpace = true;
		int tmpLinesLen = tmpLines.length;
		String currentLine;
		
		for (int i = 0; i < tmpLinesLen; i++) {
			currentLine = tmpLines[i];
			
			if (currentLine.trim().length() > 0) { 
				list.add("> " + currentLine);
				
				if (startWhiteSpace) 
					startWhiteSpace = false;
			}
			else 
				if (!startWhiteSpace) 
					list.add("\n");
		}
		
		// Leave some space for the reply
		list.add("\n");
		
		// Now make a string again
		StringBuffer retBuf = new StringBuffer(tmpLines.length+2);
		int listLen = list.size();
		String line;
		
		for (int i=0; i<listLen; i++) {
			line = list.get(i);
			retBuf.append(line);
			retBuf.append("\n");
		}

		return retBuf.toString().trim();
	}


	// ===============================================================
	// Extract the textual part of a mime multipart message body
	// ===============================================================
	public static Vector<HashMap<String, String>> extractMimeParts(String bodyText, String boundary) {

		Vector<HashMap<String, String>> mimeParts = new Vector<HashMap<String, String>>(1);
		Vector<HashMap<String, String>> tmpOtherParts = new Vector<HashMap<String, String>>();
		HashMap<String, String> bodyMap = new HashMap<String, String>();
		
		StringBuilder newBody = new StringBuilder(bodyText.length());
		
		String boundaryStart = "--" + boundary;
		String boundaryFinish = "--" + boundary + "--";
		String[] lines = bodyText.split("\n");
		
		String line = null;
		int linesLen = lines.length;
		boolean inPart = false; // Flags if the loop is inside a part
		boolean inTextPlainPart = false; // Flag if the loop is inside a text/plain part
		boolean inPartInfo = false; // Flag if we are readeding the metainfo of a part
		boolean textPlainPartFound = false;
		boolean firstPart = true;
		
		String[] partInfoTokens = null;
		String contentType = null;
		String charset       = "ISO-88591-15";
		String encoding      = "8Bit";
		String[] contentTypeValueParts = null;
		String infoName = null;
		String contentTypeKey = null;
		String contentTypeValue = null;
		HashMap<String, String> partData = new HashMap<String, String>();
		StringBuilder partContent = new StringBuilder();
		String tline = null;
		
		for (int i=0; i<linesLen; i++) {
			
			line = lines[i];
			tline = line.trim();
			if (tline.equals(boundaryStart)) { // Found boundary and start of part
				
				inPart = true;
				inPartInfo = true;
				
				if (!firstPart) {
					if (!inTextPlainPart) {
						partData.put("content", partContent.toString());
						tmpOtherParts.add(partData);
					}
					inTextPlainPart = false;
					
				} else
					firstPart = false;
				
				partData = new HashMap<String, String>();
				
			}
			
			
			else if (tline.equals(boundaryFinish)) {
				if (!inTextPlainPart) {
					partData.put("content", partContent.toString());
					tmpOtherParts.add(partData);
				}
			}
			
			else if (inPart && !inPartInfo) {
				if (inTextPlainPart)
					newBody.append(line + "\n");
				else
					partContent.append(line + "\n");
			}
			
			
			else if (line.trim().length() == 0 && inPart && inPartInfo)
				inPartInfo = false; // White line, end of the part metadata and start of the part content
			
			
			else if (line.trim().length() > 0  && line.contains(":") && inPart && inPartInfo) {
				
				partInfoTokens = line.split(":", 2);
			
				// Ex: Content-Type: TEXT/PLAIN; charset=ISO-8859-1
				infoName = partInfoTokens[0].trim();
				
				if (infoName.equalsIgnoreCase("Content-Type")) {
					
					String next = partInfoTokens[1];
					String[] tmpContentTypeTokens;
					Vector<String> contentTypeTokens = new Vector<String>();
					
					boolean finished = false;
					while (!finished) {
						tmpContentTypeTokens = next.split(";");
						for (String s : tmpContentTypeTokens)
							contentTypeTokens.add(s);
						
						if (next.trim().endsWith(";")) {
							i++;
							next = lines[i];
						} else {
							finished = true;
						}
					}

					contentType = contentTypeTokens.get(0).trim();
					if (contentType.equalsIgnoreCase("text/plain")) {
						inTextPlainPart = true;
						textPlainPartFound = true;
					} 
					else
						partData.put("type", contentType);
					
					for (String ctToken : contentTypeTokens) {

						contentTypeValueParts = ctToken.split("=", 2);
						
						if (contentTypeValueParts.length > 1) {
							contentTypeKey   = contentTypeValueParts[0].trim();
							contentTypeValue = contentTypeValueParts[1].replace("\"", "").trim();
							
							if (inTextPlainPart)  {
								if (contentTypeKey.equalsIgnoreCase("charset")) 
									charset = contentTypeValue;
								
							} else { 
								if (contentTypeKey.equalsIgnoreCase("SizeOnDisk"))
									partData.put("size", contentTypeValue);
								else if (contentTypeKey.equalsIgnoreCase("name")) {
									partData.put("name", contentTypeValue);
								}
							}
							
						}
						
					}
				}
				
				// Content-Transfer-Encoding: QUOTED-PRINTABLE
				else if (infoName.equalsIgnoreCase("Content-Transfer-Encoding")) {
					
					if (inTextPlainPart)
						encoding = partInfoTokens[1].replace("\"", "").trim();
					else
						partData.put("encoding", partInfoTokens[1].replace("\"", "").trim());
				}
					
			}
		}
		
		bodyMap.put("charset", charset);
		bodyMap.put("encoding", encoding);
		
		if (textPlainPartFound) 
			bodyMap.put("bodyText", newBody.toString());
		
   	    else  // Fallback
			bodyMap.put("bodyText", bodyText);
		
		mimeParts.add(bodyMap);
		
		int tmpOtherPartsLen = tmpOtherParts.size();
		byte[] base64data;
		byte[] decodedData;
		long size = -1;
		String path, ext, tmpName;
		
		for (int i=0; i<tmpOtherPartsLen; i++) {
			
			HashMap<String, String> partData2 = tmpOtherParts.get(i);
			base64data = partData2.get("content").getBytes();
			partData2.remove("content");
			decodedData = Base64.decodeBase64(base64data);
			base64data = null;
			
			partData2.put("md5", DigestUtils.md5Hex(decodedData));
			
			// Get the file extension and update the md5filename with it
			path = UsenetConstants.EXTERNALSTORAGE + "/" + UsenetConstants.APPNAME + "/attachments/";
			tmpName = partData2.get("name");
			ext = tmpName.substring(tmpName.lastIndexOf('.')+1, tmpName.length());
			partData2.put("md5", partData2.get("md5") + "." + ext);

			try {
				size = FSUtils.writeByteArrayToDiskFileAndGetSize(decodedData, path, partData2.get("md5"));
			} catch (IOException e) {
				Log.d("Groundhog", "Unable to save attachment " + partData2.get("name") + ":" + e.getMessage());
				e.printStackTrace();
				continue;
			}
			
			if (partData2.get("size") == null)
				partData2.put("size", Long.toString(size));
			
			partData2.put("path", path);
			mimeParts.add(partData2);
		}
		
		return mimeParts;
	}
	
	public static String getHtmlHeader(String charset) {
		StringBuilder html = new StringBuilder();
		html.append("<HTML>\n");
		html.append("<HEAD>\n");
		html.append("<meta http-equiv=\"Content-Type\" content=\"text/html;" + charset + "\">\n");
		html.append("</HEAD>\n");
		html.append("<BODY>\n");		
		return html.toString();
	}


	public static String getCharsetFromHeader(HashMap<String, String> header) 
	{
		String[] tmpContentArr = null;
		String[] contentTypeParts = null;
		String tmpFirstToken;
		
		String charset = "iso-8859-1";
		
		if (header.containsKey("Content-Type")) {
			tmpContentArr = header.get("Content-Type").split(";");
			int contentLen = tmpContentArr.length;
		
			for (int i=0; i<contentLen; i++) {
				
				contentTypeParts = tmpContentArr[i].split("=", 2);
				tmpFirstToken = contentTypeParts[0].trim();
				
				if (contentTypeParts.length > 1 && tmpFirstToken.equalsIgnoreCase("charset")) {
					// Found
					return contentTypeParts[1].replace("\"", "").trim();					
					
				}				
			}
		}		
		return charset;
	}

	
	public static String htmlizeFullHeaders(HashMap<String, String> header) {
		
		StringBuilder html = new StringBuilder();
		
		// Since this is a unsorted HashMap, put some logical order on the first fields
		if (header.containsKey("From"))
			html.append("<strong>From:</Strong> " + "<i>" + escapeHtmlWithLinks(header.get("From")) + "</i> <br/>\n");
		
		if (header.containsKey("Subject"))
			html.append("<strong>Subject:</Strong> " + "<i>" + escapeHtmlWithLinks(header.get("Subject")) + "</i><br/>\n");
		
		if (header.containsKey("Date"))
			html.append("<strong>Date:</Strong> " + "<i>" + escapeHtmlWithLinks(header.get("Date")) + "</i><br/>\n");
		
		if (header.containsKey("Newsgroups"))
			html.append("<strong>Newsgroups:</Strong> " + "<i>" + escapeHtmlWithLinks(header.get("Newsgroups")) + "</i><br/>\n");
		
		if (header.containsKey("Organization"))
			html.append("<strong>Organization:</Strong> " + "<i>" + escapeHtmlWithLinks(header.get("Organization")) + "</i><br/>\n");
		
		if (header.containsKey("Message-ID"))
			html.append("<strong>Message-ID:</Strong> " + "<i>" + escapeHtmlWithLinks(header.get("Message-ID")) + "</i><br/>\n");
		
		if (header.containsKey("References"))
			html.append("<strong>References:</Strong> " + "<i>" + escapeHtmlWithLinks(header.get("References")) + "</i><br/>\n");
		
		if (header.containsKey("Path"))
			html.append("<strong>Path:</Strong> " + "<i>" + escapeHtmlWithLinks(header.get("Path")) + "</i><br/>\n");
		
		for (String key : header.keySet()) {
			if (key.equals("From") || key.equals("Subject") || key.equals("Date") || key.equals("Newsgroups")
			    || key.equals("Message-ID") || key.equals("References") || key.equals("Organization") || key.equals("Path"))
				continue;
			html.append("<strong>" + key + ":" + "</strong>" + " <i>" + escapeHtmlWithLinks(header.get(key)) + "</i><br/>\n ");
		}
		
		html.append("<br/><br/>");
		
		return html.toString();
	}


	public static Vector<HashMap<String, String>> getUUEncodedAttachments(String bodyText) {
		
		Vector<HashMap<String, String>> bodyAttachments = new Vector<HashMap<String, String>>(1);
		String newBody = null;
		Vector<HashMap<String, String>> attachDatas = null;
		
		// Shortcut in case there are no attachment
		
		if (!bodyText.contains("begin ") || !bodyText.contains("end")) {
			newBody = bodyText;
		} 
		
		// Could have attachment, could have not; let's see
		else {
			
			StringBuilder newBodyBuilder = new StringBuilder();
			StringBuilder attachment = new StringBuilder();
			String[] bodyLines = bodyText.split("\n");
			bodyText = null;
			int bodyLinesLen = bodyLines.length;
			
			boolean inAttach = false;
			boolean firstOfTheEnd = false;
			
			String line, sline, filename = null;
			HashMap<String, String> attachData = null;
			
			attachDatas = new Vector<HashMap<String, String>>();
			
			for (int i=0; i<bodyLinesLen; i++) {
				
				line  = bodyLines[i];
				sline = line.trim();
				
				if (sline.equals("`")) {
					firstOfTheEnd = true;
					attachment.append(line + "\n");
				}
				
				else if (firstOfTheEnd && inAttach && sline.equals("end")) {
					
					attachment.append(line + "\n");
					if (attachDatas == null)
						attachDatas = new Vector<HashMap<String, String>>();
					
					try {
						attachData = FSUtils.saveUUencodedAttachment(attachment.toString(), filename);
						attachDatas.add(attachData);
					} catch (IOException e) {
						e.printStackTrace();
					} catch (UsenetReaderException e) {
						// XXX YYY ZZZ: Poner mensaje de adjunto incorrecto?
						e.printStackTrace();
					}
					attachment = null;
					inAttach = false;
					firstOfTheEnd = false;
				} 
				
				else if (firstOfTheEnd && inAttach && !sline.equals("end")) {
					firstOfTheEnd = false; // False alarm?
				}
				
				// XXX YYY ZZZ: ESTO NO SOPORTA UUENCODED SIN PERMISOS!!!
				else if (sline.length() >= 11 && sline.substring(0, 6).equals("begin ") 
						  && Character.isDigit(sline.charAt(6))
						  && Character.isDigit(sline.charAt(7))
						  && Character.isDigit(sline.charAt(8))
						  && Character.isWhitespace(sline.charAt(9))
						  && !Character.isWhitespace(sline.charAt(10))) {
					
					filename = sline.substring(10);
					inAttach = true;
					attachment.append(line + "\n");
				}
				
				else if (inAttach) {
					attachment.append(line + "\n");
				}
				
				else {
					newBodyBuilder.append(line + "\n");
				}
								  
			}
			newBody = newBodyBuilder.toString();
		}
		
		// Add the new body as first element
		HashMap<String, String> bodyMap = new HashMap<String, String>(1);
		bodyMap.put("body", newBody);
		bodyAttachments.add(bodyMap);
		
		
		if (attachDatas != null) {
			for (HashMap<String, String> attData : attachDatas) {
				bodyAttachments.insertElementAt(attData, 1);
			}
		}
		
		return bodyAttachments;
	}


	public static String getAttachmentsHtml(Vector<HashMap<String, String>> mimePartsVector) {

		if (mimePartsVector == null || mimePartsVector.size() == 0)
			return "<!-- No attachments -->\n";
		
		String retString = null;

		if (mimePartsVector == null || mimePartsVector.size() == 0) 
			retString = "";
		
		else {
			
			StringBuilder returnHtml = new StringBuilder();
			returnHtml.append("<I>Attachments:</i><BR/>\n");
			returnHtml.append("<hr>");
			returnHtml.append("<table>\n");
			
			for (HashMap<String, String> attachData : mimePartsVector) {
				returnHtml.append("<tr bgcolor=\"#FFFF00\">");
				returnHtml.append("<td>\n");
				returnHtml.append("<A HREF=\"attachment://fake.com/" + attachData.get("md5") + "\">" + 
						          attachData.get("name") + "</A><BR/>\n");
				returnHtml.append("</td>\n");
				
				returnHtml.append("<td>\n");
				returnHtml.append(attachData.get("type"));
				returnHtml.append("</td>\n");
				
				returnHtml.append("<td>\n");
				returnHtml.append(new Integer(attachData.get("size"))/1024);
				returnHtml.append(" KB");
				returnHtml.append("</td>\n");
				
				returnHtml.append("</tr>\n");
			}
			
			returnHtml.append("</table>\n");
			returnHtml.append("<hr>");
			retString = returnHtml.toString();
		}
		return retString;
	}



	// ====================================================================
	// Short lines to more or less 70 chars, breaking by spaces if possible
	// ====================================================================
	public static String shortenPostLines(String body) {
		StringBuilder builder = new StringBuilder();
		
		String[] lines = body.split("\n");
		int indexSpace;
		
		for (String line : lines) {
			
			if (line.length() > 70) {
				
				while(true) {
					indexSpace = line.substring(0, 70).lastIndexOf(' ');
					if (indexSpace == -1)
						indexSpace = 70;
  				    builder.append(line.substring(0, indexSpace + 1) + "\n");
					line = line.substring(indexSpace + 1);
					if (line.length() < 70) {
						builder.append(line + "\n");
						break;
					}
				}
			}
			
			else {
				builder.append(line + "\n");
			}
		}
		
		return builder.toString();
	}
}



