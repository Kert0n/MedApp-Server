package org.kert0n.medappserver.api

/**
 * Source-only aliases for older integration-story fixture code. They do not exist in the
 * production artifact or the OpenAPI contract.
 */
typealias ConsumeRequest = DrugConsumptionRequest
typealias DrugUpdateDTO = DrugPatchRequest
typealias UsingDTO = TreatmentPlanDTO
typealias UsingCreateDTO = TreatmentPlanCreateRequest
typealias UsingUpdateDTO = TreatmentPlanPatchRequest
