package io.mosip.registration.processor.reprocessor.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.registration.processor.core.abstractverticle.MessageBusAddress;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.abstractverticle.MosipEventBus;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.code.ModuleName;
import io.mosip.registration.processor.core.code.RegistrationExceptionTypeCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionStatusCode;
import io.mosip.registration.processor.core.code.WorkflowActionCode;
import io.mosip.registration.processor.core.constant.RegistrationType;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.PacketManagerException;
import io.mosip.registration.processor.core.exception.WorkflowActionException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.exception.util.PlatformSuccessMessages;
import io.mosip.registration.processor.core.logger.LogDescription;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.util.MessageBusUtil;
import io.mosip.registration.processor.core.workflow.dto.WorkflowCompletedEventDTO;
import io.mosip.registration.processor.packet.storage.utils.PacketManagerService;
import io.mosip.registration.processor.reprocessor.util.WebSubUtil;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.retry.verticle.constants.ReprocessorConstants;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * The Class WorkflowActionService.
 */
@Component
public class WorkflowActionService {
	
	/** The registration status service. */
	@Autowired
	RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** The packet manager service. */
	@Autowired
	private PacketManagerService packetManagerService;

	/** The core audit request builder. */
	@Autowired
	AuditLogRequestBuilder auditLogRequestBuilder;

	/** The web sub util. */
	@Autowired
	WebSubUtil webSubUtil;

	/** The hot listed tag. */
	@Value("${mosip.regproc.workflow.action.hotlisted.tag")
	private String hotListedTag;

	/** The Constant USER. */
	private static final String USER = "MOSIP_SYSTEM";


	/** The resume from beginning stage. */
	@Value("${mosip.regproc.workflow.action.resumefrombeginning.stage")
	private String resumeFromBeginningStage;

	/** The module id. */
	public static String MODULE_ID = PlatformSuccessMessages.RPR_WORKFLOW_ACTION_SERVICE_SUCCESS.getCode();

	/** The module name. */
	public static String MODULE_NAME = ModuleName.WORKFLOW_ACTION_SERVICE.toString();

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(WorkflowActionService.class);

	/**
	 * Process workflow action.
	 *
	 * @param workflowIds    the workflow ids
	 * @param workflowAction the workflow action
	 * @param mosipEventBus  the mosip event bus
	 * @throws WorkflowActionException the workflow action exception
	 */
	public void processWorkflowAction(List<String> workflowIds, String workflowAction,
			MosipEventBus mosipEventBus) throws WorkflowActionException {
		WorkflowActionCode workflowActionCode = null;
		try {
			workflowActionCode = WorkflowActionCode.valueOf(workflowAction);
		switch (workflowActionCode) {
		case RESUME_PROCESSING:
				processResumeProcessing(workflowIds, mosipEventBus, workflowActionCode);
		case RESUME_PROCESSING_AND_REMOVE_HOTLISTED_TAG:
				processResumeProcessingAndRemoveHotlistedTag(workflowIds, mosipEventBus, workflowActionCode);
		case RESUME_FROM_BEGINNING:
				processResumeFromBeginning(workflowIds, mosipEventBus, workflowActionCode);
		case RESUME_FROM_BEGINNING_AND_REMOVE_HOTLISTED_TAG:
				processResumeFromBeginningAndRemoveHotlistedTag(workflowIds, mosipEventBus,
					workflowActionCode);
		case STOP_PROCESSING:
				processStopProcessing(workflowIds, workflowActionCode);
		default:
				throw new WorkflowActionException(PlatformErrorMessages.RPR_WAS_UNKNOWN_WORKFLOW_ACTION.getCode(),
						PlatformErrorMessages.RPR_WAS_UNKNOWN_WORKFLOW_ACTION.getMessage());

			}
		} catch (ApisResourceAccessException e) {
			logAndThrowError(e, e.getErrorCode(), e.getMessage(), workflowActionCode);


		} catch (PacketManagerException e) {
			logAndThrowError(e, e.getErrorCode(), e.getMessage(), workflowActionCode);


		} catch (JsonProcessingException e) {
			logAndThrowError(e, e.getErrorCode(), e.getMessage(), workflowActionCode);


		} catch (IOException e) {
			logAndThrowError(e, PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getCode(),
					PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getMessage(), workflowActionCode);

		}
	}

	/**
	 * Log and throw error.
	 *
	 * @param e                  the e
	 * @param errorCode          the error code
	 * @param errorMessage       the error message
	 * @param workflowActionCode the workflow action code
	 * @throws WorkflowActionException the workflow action exception
	 */
	private void logAndThrowError(Exception e, String errorCode, String errorMessage,
			WorkflowActionCode workflowActionCode) throws WorkflowActionException {
		regProcLogger.error("Error in  processWorkflowAction  for WorkflowAction  {} {} {} {}",
				workflowActionCode.name(),
				errorMessage, e.getMessage(), ExceptionUtils.getStackTrace(e));
		throw new WorkflowActionException(errorCode, errorMessage);
	}

	/**
	 * Process stop processing.
	 *
	 * @param workflowIds        the workflow ids
	 * @param workflowActionCode the workflow action code
	 */
	private void processStopProcessing(List<String> workflowIds,
			WorkflowActionCode workflowActionCode) {
		regProcLogger.debug("processStopProcessing called for workflowIds {}", workflowIds);
		LogDescription description = new LogDescription();
		if (!CollectionUtils.isEmpty(workflowIds)) {
			for (String rid : workflowIds) {

					InternalRegistrationStatusDto registrationStatusDto = getAndUpdateRegistrationStatus(
						workflowActionCode, rid, RegistrationStatusCode.REJECTED);
				sentWebSubEvent(registrationStatusDto);
					description.setMessage(
							String.format(PlatformSuccessMessages.RPR_WORKFLOW_ACTION_SERVICE_SUCCESS.getMessage(),
									workflowActionCode.name()));
					updateAudit(description, rid);

			}
			regProcLogger.debug("processStopProcessing call ended for workflowIds {}", workflowIds);
		}

	}

	/**
	 * Sent web sub event.
	 *
	 * @param registrationStatusDto the registration status dto
	 */
	private void sentWebSubEvent(InternalRegistrationStatusDto registrationStatusDto) {
		WorkflowCompletedEventDTO workflowCompletedEventDTO = new WorkflowCompletedEventDTO();
		workflowCompletedEventDTO.setInstanceId(registrationStatusDto.getRegistrationId());
		workflowCompletedEventDTO.setResultType(registrationStatusDto.getStatusCode());
		workflowCompletedEventDTO.setWorkflowType(registrationStatusDto.getRegistrationType());
		workflowCompletedEventDTO.setErrorCode(RegistrationExceptionTypeCode.PACKET_REJECTED.name());
		webSubUtil.publishSuccess(workflowCompletedEventDTO);

	}

	/**
	 * Process resume from beginning and remove hotlisted tag.
	 *
	 * @param workflowIds        the workflow ids
	 * @param mosipEventBus      the mosip event bus
	 * @param workflowActionCode the workflow action code
	 * @throws JsonParseException          the json parse exception
	 * @throws JsonMappingException        the json mapping exception
	 * @throws ApisResourceAccessException the apis resource access exception
	 * @throws JsonProcessingException     the json processing exception
	 * @throws JsonProcessingException     the json processing exception
	 * @throws PacketManagerException      the packet manager exception
	 * @throws IOException                 Signals that an I/O exception has
	 *                                     occurred.
	 * @throws WorkflowActionException     the workflow action exception
	 */
	private void processResumeFromBeginningAndRemoveHotlistedTag(List<String> workflowIds,
			MosipEventBus mosipEventBus, WorkflowActionCode workflowActionCode)
			throws JsonParseException, JsonMappingException, ApisResourceAccessException, JsonProcessingException,
			PacketManagerException, com.fasterxml.jackson.core.JsonProcessingException, IOException,
			WorkflowActionException {
		regProcLogger.debug("processResumeFromBeginningAndRemoveHotlistedTag called for workflowIds {}", workflowIds);

		LogDescription description = new LogDescription();
		if (!CollectionUtils.isEmpty(workflowIds)) {
			for (String rid : workflowIds) {

				if (removeHotlistedTag(rid)) {

					InternalRegistrationStatusDto registrationStatusDto = getAndUpdateRegistrationStatus(
							workflowActionCode, rid, RegistrationStatusCode.PROCESSING);
					sentPacketEventforResumeBeginnning(mosipEventBus, registrationStatusDto);
					description.setMessage(
							String.format(PlatformSuccessMessages.RPR_WORKFLOW_ACTION_SERVICE_SUCCESS.getMessage(),
									workflowActionCode.name()));
					updateAudit(description, rid);

				} else {
					throw new WorkflowActionException(
							PlatformErrorMessages.RPR_WAS_REMOVE_HOTLISTED_TAG_FAILED.getCode(),
							PlatformErrorMessages.RPR_WAS_REMOVE_HOTLISTED_TAG_FAILED.getMessage());
				}

			}
			regProcLogger.debug("processResumeFromBeginningAndRemoveHotlistedTag call ended for workflowIds {}",
					workflowIds);
		}

	}



	/**
	 * Process resume from beginning.
	 *
	 * @param workflowIds        the workflow ids
	 * @param mosipEventBus      the mosip event bus
	 * @param workflowActionCode the workflow action code
	 */
	private void processResumeFromBeginning(List<String> workflowIds,
			MosipEventBus mosipEventBus, WorkflowActionCode workflowActionCode) {
		regProcLogger.debug("processResumeFromBeginning called for workflowIds {}", workflowIds);
		LogDescription description = new LogDescription();
		if (!CollectionUtils.isEmpty(workflowIds)) {
			for (String rid : workflowIds) {

				InternalRegistrationStatusDto registrationStatusDto = getAndUpdateRegistrationStatus(workflowActionCode,
						rid, RegistrationStatusCode.PROCESSING);

				sentPacketEventforResumeBeginnning(mosipEventBus, registrationStatusDto);
				description.setMessage(
						String.format(PlatformSuccessMessages.RPR_WORKFLOW_ACTION_SERVICE_SUCCESS.getMessage(),
								workflowActionCode.name()));
				updateAudit(description, rid);

			}
			regProcLogger.debug("processResumeFromBeginning call ended for workflowIds {}", workflowIds);
		}

	}


	/**
	 * Process resume processing and remove hotlisted tag.
	 *
	 * @param workflowIds        the workflow ids
	 * @param mosipEventBus      the mosip event bus
	 * @param workflowActionCode the workflow action code
	 * @throws JsonParseException          the json parse exception
	 * @throws JsonMappingException        the json mapping exception
	 * @throws ApisResourceAccessException the apis resource access exception
	 * @throws JsonProcessingException     the json processing exception
	 * @throws JsonProcessingException     the json processing exception
	 * @throws PacketManagerException      the packet manager exception
	 * @throws IOException                 Signals that an I/O exception has
	 *                                     occurred.
	 * @throws WorkflowActionException     the workflow action exception
	 */
	private void processResumeProcessingAndRemoveHotlistedTag(List<String> workflowIds,
			MosipEventBus mosipEventBus, WorkflowActionCode workflowActionCode)
			throws JsonParseException, JsonMappingException, ApisResourceAccessException, JsonProcessingException,
			PacketManagerException, com.fasterxml.jackson.core.JsonProcessingException, IOException,
			WorkflowActionException {

		regProcLogger.debug("processResumeProcessingAndRemoveHotlistedTag called for workflowIds {}", workflowIds);

		LogDescription description = new LogDescription();
		if (!CollectionUtils.isEmpty(workflowIds)) {
		for(String rid:workflowIds) {
				if (removeHotlistedTag(rid)) {

					InternalRegistrationStatusDto registrationStatusDto = getAndUpdateRegistrationStatus(
							workflowActionCode, rid, RegistrationStatusCode.PROCESSING);

					sentPacketEventForResumeProcessing(mosipEventBus, registrationStatusDto);
					description.setMessage(
							String.format(PlatformSuccessMessages.RPR_WORKFLOW_ACTION_SERVICE_SUCCESS.getMessage(),
									workflowActionCode.name()));
					updateAudit(description, rid);
					} else {
					throw new WorkflowActionException(
							PlatformErrorMessages.RPR_WAS_REMOVE_HOTLISTED_TAG_FAILED.getCode(),
							PlatformErrorMessages.RPR_WAS_REMOVE_HOTLISTED_TAG_FAILED.getMessage());
					}
			}
			regProcLogger.debug("processResumeProcessingAndRemoveHotlistedTag call ended for workflowIds {}",
					workflowIds);
		}

	}

	/**
	 * Process resume processing.
	 *
	 * @param workflowIds        the workflow ids
	 * @param mosipEventBus      the mosip event bus
	 * @param workflowActionCode the workflow action code
	 */
	private void processResumeProcessing(List<String> workflowIds, MosipEventBus mosipEventBus,
			WorkflowActionCode workflowActionCode) {
		regProcLogger.debug("processResumeProcessing called for workflowIds {}", workflowIds);
		LogDescription description = new LogDescription();
		if (!CollectionUtils.isEmpty(workflowIds)) {
			for (String rid : workflowIds) {


				InternalRegistrationStatusDto registrationStatusDto = getAndUpdateRegistrationStatus(workflowActionCode,
						rid, RegistrationStatusCode.PROCESSING);

				sentPacketEventForResumeProcessing(mosipEventBus, registrationStatusDto);
				description.setMessage(
						String.format(PlatformSuccessMessages.RPR_WORKFLOW_ACTION_SERVICE_SUCCESS.getMessage(),
								workflowActionCode.name()));
				updateAudit(description, rid);

			}
			regProcLogger.debug("processResumeProcessing call ended for workflowIds {}",
					workflowIds);
		}

	}

	/**
	 * Sent packet event for resume processing.
	 *
	 * @param mosipEventBus         the mosip event bus
	 * @param registrationStatusDto the registration status dto
	 */
	private void sentPacketEventForResumeProcessing(MosipEventBus mosipEventBus,
			InternalRegistrationStatusDto registrationStatusDto) {
		String stageName = MessageBusUtil
				.getMessageBusAdress(registrationStatusDto.getRegistrationStageName());
		if (RegistrationTransactionStatusCode.SUCCESS.name()
				.equalsIgnoreCase(registrationStatusDto.getLatestTransactionStatusCode())) {
			stageName = stageName.concat(ReprocessorConstants.BUS_OUT);
		} else {
			stageName = stageName.concat(ReprocessorConstants.BUS_IN);
		}
		MessageDTO object = new MessageDTO();
		object.setRid(registrationStatusDto.getRegistrationId());
		object.setIsValid(true);
		object.setReg_type(RegistrationType.valueOf(registrationStatusDto.getRegistrationType()));
		MessageBusAddress address = new MessageBusAddress(stageName);
		sendMessage(object, address, mosipEventBus);
	}

	/**
	 * Sent packet eventfor resume beginnning.
	 *
	 * @param mosipEventBus         the mosip event bus
	 * @param registrationStatusDto the registration status dto
	 */
	private void sentPacketEventforResumeBeginnning(MosipEventBus mosipEventBus,
			InternalRegistrationStatusDto registrationStatusDto) {
		String stageName = MessageBusUtil.getMessageBusAdress(resumeFromBeginningStage);
		stageName = stageName.concat(ReprocessorConstants.BUS_IN);
		MessageDTO object = new MessageDTO();
		object.setRid(registrationStatusDto.getRegistrationId());
		object.setIsValid(true);
		object.setReg_type(RegistrationType.valueOf(registrationStatusDto.getRegistrationType()));
		MessageBusAddress address = new MessageBusAddress(stageName);
		sendMessage(object, address, mosipEventBus);

	}

	/**
	 * Send message.
	 *
	 * @param object        the object
	 * @param address       the address
	 * @param mosipEventBus the mosip event bus
	 */
	private void sendMessage(MessageDTO object, MessageBusAddress address,
			MosipEventBus mosipEventBus) {
		regProcLogger.debug("sendMessage called for workflowId and address {} {}", object.getRid(),
				address.getAddress());
		mosipEventBus.send(address, object);
	}



	/**
	 * Removes the hotlisted tag.
	 *
	 * @param rid the rid
	 * @return true, if successful
	 * @throws JsonParseException          the json parse exception
	 * @throws JsonMappingException        the json mapping exception
	 * @throws ApisResourceAccessException the apis resource access exception
	 * @throws JsonProcessingException     the json processing exception
	 * @throws JsonProcessingException     the json processing exception
	 * @throws PacketManagerException      the packet manager exception
	 * @throws IOException                 Signals that an I/O exception has
	 *                                     occurred.
	 */
	private boolean removeHotlistedTag(String rid)
			throws JsonParseException, JsonMappingException, ApisResourceAccessException, JsonProcessingException,
			PacketManagerException, com.fasterxml.jackson.core.JsonProcessingException, IOException {
		List<String> deleteTags = new ArrayList<String>();
		deleteTags.add(hotListedTag);
		regProcLogger.debug("removeHotlistedTag called for workflowId and hotListedTag {} {}", rid, hotListedTag);
		return packetManagerService.deleteTags(rid, deleteTags);

	}

	/**
	 * Update audit.
	 *
	 * @param description    the description
	 * @param registrationId the registration id
	 */
	private void updateAudit(LogDescription description, String registrationId) {

		String eventId = EventId.RPR_402.toString();
		String eventName = EventName.UPDATE.toString();
		String eventType = EventType.BUSINESS.toString();

		auditLogRequestBuilder.createAuditRequestBuilder(description.getMessage(), eventId, eventName, eventType,
				MODULE_ID, MODULE_NAME, registrationId);
	}

	/**
	 * Gets the and update registration status.
	 *
	 * @param workflowActionCode the workflow action code
	 * @param rid                the rid
	 * @param statusCode         the status code
	 * @return the and update registration status
	 */
	private InternalRegistrationStatusDto getAndUpdateRegistrationStatus(WorkflowActionCode workflowActionCode,
			String rid, RegistrationStatusCode statusCode) {
		InternalRegistrationStatusDto registrationStatusDto = registrationStatusService.getRegistrationStatus(rid);
		registrationStatusDto.setStatusCode(statusCode.name());
		registrationStatusDto.setStatusComment(String.format(
				PlatformSuccessMessages.RPR_WORKFLOW_ACTION_SERVICE_SUCCESS.getMessage(), workflowActionCode.name()));

		LocalDateTime updateTimeStamp = DateUtils.getUTCCurrentDateTime();
		registrationStatusDto.setUpdateDateTime(updateTimeStamp);
		registrationStatusDto.setUpdatedBy(USER);

		registrationStatusService.updateRegistrationStatusForWorkflow(registrationStatusDto, MODULE_ID, MODULE_NAME);
		return registrationStatusDto;
	}
}
