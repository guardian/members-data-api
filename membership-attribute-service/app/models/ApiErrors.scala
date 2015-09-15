package models

object ApiErrors {
  def badRequest(msg: String): ApiError =
    ApiError(
      message = "Bad Request",
      details = msg,
      statusCode = 400
    )

  val notFound: ApiError =
    ApiError(
      message = "Not found",
      details = "Not Found",
      statusCode = 404
    )

  val internalError: ApiError =
    ApiError(
      message = "Internal Server Error",
      details = "Internal Server Error",
      statusCode = 500
    )

  def unauthorized(msg: String) =
    ApiError(
      message = "Unauthorised",
      details = msg,
      statusCode = 401
    )

  val cookiesRequired: ApiError = unauthorized("Valid GU_U and SC_GU_U cookies are required.")
}
